package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W3 L2 — the ONE-ENTITY pod
 * lane: the per-round, seat-keyed flight table for the command shuttle that IS the seat's
 * hull for the whole flight ("own-hull-IS-pod"). At BEGIN the serve lane spawns the pod and
 * commits the seat onto it at once (pod → the seat's wire id S, the source → a fresh id F);
 * a dock re-keys the seat onto the target and only THEN removes the pod; a flight with no
 * good end STRANDS in place — the body flips to the pilotable {@code shuttlepod_cc_pod}
 * shape ({@link Port#toPilotable}) and the seat simply keeps flying it. Nothing is ever
 * despawned-and-redeployed, nothing is left behind (the old two-entity strand worked but was janky).
 * Pure state machine over an engine {@link Port}
 * (the {@link cc.starsector.server.bridge.TransferPodSpawner} bridge live; a recording fake
 * in the contract rows): the lane never touches reflection, the arbiter, or the wire.
 * Pumped by {@code CommandTransferServeLane} BEFORE its drain each tick, so a dock this tick
 * commits this tick.
 *
 * <p>Phases: FLYING (seeking the target; the engine advances the vanilla
 * {@code TransferCommandAI} itself), DOCKED (landing animation
 * finished; holds for the drain's commit consume), RETURNING (aborted with a live source —
 * flying home; the seat is aboard, so a new begin RETARGETS it, never despawns it),
 * RETURNED (the return landing finished; holds for the serve lane's same-pump DOCK-BACK
 * onto the source under F, which ends in {@link #despawnFlight}). EVERY former fail-closed
 * despawn site (orphan / retarget-lost / pod-lost / landing-lost) is now a STRAND — the
 * seat is aboard, so the pod may never vanish from under it; only the dock consumes
 * ({@link #despawnFlight}) and the round wrap ({@link #clearRound}) remove a body.
 *
 * <p>The {@code transfer.podTimeoutSeconds} knob ({@link #parseTimeoutTicks} — the trio's
 * strict junk-THROWS boot law) bounds a flight's wall clock; on expiry the pump reports the
 * seat (the serve lane aborts the arbiter row + fans; its abort end decides return-home vs
 * strand). Timeout 0 = off. Sim-thread only, like every serve lane.
 */
public final class TransferPodLane {

    /** Engine seam the lane drives; every method total (no throws) — failures return
     * null/false/NaN and the lane strands fail-closed. Live impl = TransferPodSpawner. */
    public interface Port {
        /** Engine ship for the registry stable id, or null (left the field / read failed). */
        Object resolveShipOrNull(String stableId);
        /** Build + addObject the vanilla-verbatim pod, or null = deny. */
        Object spawnPodOrNull(Object sourceShip, Object targetShip);
        /** Vanilla's own mid-flight redirect ({@code TransferCommandAI.setTo}). */
        boolean retargetPod(Object pod, Object newTarget);
        /** The pod's engine stable id (trimmed non-blank), or null — the L4 S1 wire-identity
         * read ({@code getId} through the spawner's existing handle). */
        String stableIdOrNull(Object pod);
        /** The vanilla ±1000-outside map-bound clamp, belt-tier. */
        void clampToMap(Object pod);
        /** Engine {@code Ship.isAlive()} (= !isHulk
         * && hp&gt;0 && in-engine — false for a hulk, a fractured piece, or a removed body).
         * Total: null / read failure reads false (a dead-or-unreadable end is the strand
         * side of the both-dead law, never a blind return-home). */
        boolean isAlive(Object ship);
        float distanceOrNaN(Object shipA, Object shipB);
        boolean isLanding(Object pod);
        /** {@code beginLandingAnimation(landOn)} — byte-verified headless-clean. */
        boolean beginLanding(Object pod, Object landOn);
        boolean isFinishedLanding(Object pod);
        /** Lift a landing-or-docked pod back off ({@code Ship.abortLanding()},
         * public — isLanding false, the launch animation replays); false = the engine refused
         * or the read threw (the caller strands instead). A no-op true on a pod not landing. */
        boolean abortLanding(Object pod);
        /** A PILOTABLE pod body resumes a flight IN PLACE — spec flip
         * cc_pod → xfer (a clone of the registry singleton), collision SHIP → NONE,
         * {@code setAI(new TransferCommandAI(body, target))}. False = deny (the body stays
         * pilotable, nothing mutated). */
        boolean toFlight(Object body, Object target);
        /** STRAND in place — the flying pod becomes the seat's pilotable
         * hull: abortLanding if landing + sinceLaunch past the lift-off input gate,
         * spec flip xfer → cc_pod, collision NONE → SHIP, {@code setAI(null)}, velocity zero,
         * pose pushed clear of {@code landedOnOrNull}'s collision radius (a landing/docked pod
         * sits INSIDE its target's bounds) and clamped 250 su inside the map
         * (the engine's edge push starts 200 su inside). Total; false = a step threw
         * (LOUD in the impl) — the caller still strands the flight (the seat is aboard). */
        boolean toPilotable(Object pod, Object landedOnOrNull);
        /** The ship is a LIVE stranded pod body (variant
         * {@code shuttlepod_cc_pod} + alive) — the resume-in-place source class. A dead cc_pod
         * wreck reads false (an ordinary hulk source: a fresh pod launches from it). */
        boolean isPodBody(Object ship);
        /** removeObject + wire-exclusion release; total (never throws). */
        void despawnPod(Object pod);
    }

    private enum Phase { FLYING, DOCKED, RETURNING, RETURNED }

    /** {@link #spawnFlight}'s verdict. */
    public enum Begin {
        /** Fail-closed deny (no port / unresolvable hulls / spawner deny) — the caller aborts
         * the arbiter row LOUD; nothing mutated. */
        DENIED,
        /** A fresh pod was spawned from the source (the seat is NOT yet aboard — the caller
         * commits it on at once). */
        SPAWNED,
        /** The source WAS a pilotable pod body: no spawn, the body flipped to flight in
         * place ({@link Port#toFlight}); the flight has no home (source null, F = 0). */
        RESUMED_POD_BODY,
        /** A RETURNING/RETURNED straggler was re-aimed at the new target (the seat is aboard
         * — never a despawn); the caller re-fans BEGIN under the same pod id. */
        RETARGETED_RETURNING,
        /** Defensive: the seat already has a FLYING/DOCKED flight (the drain's hasPod guard
         * makes this unreachable) — nothing to do. */
        ALREADY_FLYING
    }

    /** {@link #abortToReturning}'s verdict. */
    public enum AbortEnd {
        /** No flight to end (pre-mint / already consumed). */
        NONE,
        /** The pod turned home toward the live source (or already was) — the park stays;
         * the RETURNED event's dock-back ends the episode. */
        RETURNING,
        /** The flight ended IN PLACE: the body is pilotable, the flight is gone, the seat
         * flies the pod. The caller ends the park (unpark + release the source pin by F,
         * NEVER a detach by S — S is the pod). */
        STRANDED
    }

    /** A {@link #pump} report. {@code sourceWireId} = F (0 for a pod-body
     * resume) — read off the flight BEFORE a STRANDED removal, so the caller can release
     * the source's pin without a lane read-back. */
    public enum EventKind { TIMED_OUT, RETURNED, STRANDED }

    public record PodEvent(int seatId, EventKind kind, long sourceWireId) {}

    private static final class Flight {
        /** The seat's body for the whole flight (own-hull-IS-pod). */
        final Object pod;
        /** Nullable: a pod-body resume has no home. */
        final Object source;
        /** F — the source's fresh wire id once the serve lane re-keyed it (0 = none). */
        long sourceWireId;
        /** Non-final: a live-target timeout SUSPENDS by re-basing
         * this to the current tick (vanilla has no give-up timer — our knob must not abort
         * a flight whose destination still lives). */
        long spawnTick;
        Object target;
        long targetWireId;
        /** S — the seat's wire id, noted by the serve lane's mint branch (0 until then). */
        long podWireId;
        Phase phase = Phase.FLYING;

        Flight(Object pod, Object source, Object target, long targetWireId, long spawnTick) {
            this.pod = pod;
            this.source = source;
            this.target = target;
            this.targetWireId = targetWireId;
            this.spawnTick = spawnTick;
        }
    }

    private enum LandingStep { PROGRESS, LANDED, FAILED }

    /** Vanilla dock/land radius. */
    private static final float DOCK_RANGE = 200f;

    /** Nullable — contract fakes may bind none: spawns then deny fail-soft, so a port-less
     * lane can never hold a flight and every other verb is unreachable-safe. */
    private final Port port;
    private final long timeoutTicks;
    private final int round;
    private final LinkedHashMap<Integer, Flight> flights = new LinkedHashMap<>();
    /** Cumulative FRESH pod spawns this round (a pod-body resume must not
     * move it — the podtest driver's zero-spawn law). */
    private int podSpawns;

    /**
     * A strand whose
     * pilotable flip the port REFUSED ({@link Port#toPilotable} false = a caught engine throw)
     * used to end the flight with the seat aboard an unflyable body — authority still
     * "mounted" (no fly-in, {@code seatsDueForFlyIn} gates on {@code !mounted}), no flight,
     * no event ever again = a round-long stuck seat, recoverable only by the seat's own
     * {@code /transfer}. The body now waits HERE and the flip is RETRIED at every pump (a
     * transient mid-animation refusal converges, the pod stays the seat's hull); after
     * {@link #FLIP_RETRY_LIMIT} refusals the body LEAVES the world ({@link Port#despawnPod})
     * so the seat-despawn observer records the mount loss under the seat's wire id (the same
     * path a pod shot down mid-flight rides) and the ordinary fly-in re-fields the seat — one
     * body at every moment, never two, no operator action. A re-begin while a retry is pending
     * DENIES ({@link #spawnFlight}: the body's shape is unknown mid-throw — a second spawn from
     * it would be the duplicate).
     */
    private static final class Unflipped {
        final Object pod;
        final Object landedOn;
        int attempts;

        Unflipped(Object pod, Object landedOn) {
            this.pod = pod;
            this.landedOn = landedOn;
        }
    }

    /** Refused pilotable flips retried per pump before the body is given up: ~2 s at
     * the 60 Hz sim step — long past any landing animation, short enough that a stuck seat
     * re-fields within the round. Public for the contract rows. */
    public static final int FLIP_RETRY_LIMIT = 120;

    /** seatId → the stranded body whose pilotable flip is still refused. */
    private final LinkedHashMap<Integer, Unflipped> unflipped = new LinkedHashMap<>();

    public TransferPodLane(Port portOrNull, long timeoutTicks, int round) {
        if (timeoutTicks < 0) {
            throw new IllegalArgumentException("timeoutTicks must be >= 0: " + timeoutTicks);
        }
        this.port = portOrNull;
        this.timeoutTicks = timeoutTicks;
        this.round = round;
    }

    /** The {@code transfer.podTimeoutSeconds} boot parse — the knob-trio law verbatim
     * (strict, LOUD, seconds converted at the serve's EFFECTIVE tick rate; 0 = no timeout). */
    public static long parseTimeoutTicks(String raw, long ticksPerSecond) {
        if (ticksPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "transfer.podTimeoutSeconds needs ticksPerSecond > 0, got: " + ticksPerSecond);
        }
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("transfer.podTimeoutSeconds must not be blank"
                    + " (expected an integer >= 0; 0 = no timeout)");
        }
        long seconds;
        try {
            seconds = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("transfer.podTimeoutSeconds needs an integer"
                    + " >= 0 (0 = no timeout), got: '" + raw + "'");
        }
        if (seconds < 0) {
            throw new IllegalArgumentException("transfer.podTimeoutSeconds needs an integer"
                    + " >= 0 (0 = no timeout), got: " + seconds);
        }
        return Math.multiplyExact(seconds, ticksPerSecond);
    }

    /** True while the pump has work: a flight in the table, or a stranded body whose refused
     * pilotable flip is still being retried (the serve lane gates {@link #pump} on this). */
    public boolean hasFlights() {
        return !flights.isEmpty() || !unflipped.isEmpty();
    }

    /** The seat's stranded body is still waiting on a refused pilotable flip. */
    public boolean hasPendingFlip(int seatId) {
        return unflipped.containsKey(seatId);
    }

    /** Fresh pod spawns so far this round (resume/retarget never count). */
    public int podSpawns() {
        return podSpawns;
    }

    /** The seat has ANY flight in the table — RETURNING/RETURNED included,
     * unlike {@link #hasPod} (the "you have no ship" winner re-check must never abort a seat
     * whose body is physically in the world — that body IS the pod). */
    public boolean hasFlight(int seatId) {
        return flights.containsKey(seatId);
    }

    /** The seat has a pod flying/docked for ITS OWN current row (a RETURNING/RETURNED
     * straggler is residue of a consumed row, so it reads false — the P3 drain re-begins
     * over it, which RETARGETS the same body). */
    public boolean hasPod(int seatId) {
        Flight flight = flights.get(seatId);
        return flight != null && (flight.phase == Phase.FLYING || flight.phase == Phase.DOCKED);
    }

    /** The P3 dock gate: the seat's pod finished its landing on the target. */
    public boolean isDocked(int seatId) {
        Flight flight = flights.get(seatId);
        return flight != null && flight.phase == Phase.DOCKED;
    }

    /** The seat's pod is inside the engine's landing animation
     * or already docked — the COMMIT window; the return-home drain refuses an abort here
     * (kept as-is: the flight commits this tick or the next). */
    public boolean isLandingOrDocked(int seatId) {
        Flight flight = flights.get(seatId);
        return flight != null && (flight.phase == Phase.DOCKED
                || (flight.phase == Phase.FLYING && port.isLanding(flight.pod)));
    }

    /** L4 S1: the seat's live pod's engine stable id through the port, or null
     * (no flight / unreadable id — the caller aborts LOUD: an unidentifiable pod must not
     * exist on a lifted wire). */
    public String podStableIdOrNull(int seatId) {
        Flight flight = flights.get(seatId);
        return flight == null ? null : port.stableIdOrNull(flight.pod);
    }

    /** The seat's live pod's ENGINE HANDLE (the spawner's own object), or
     * null without a flight — the mount re-key needs it because a same-tick spawn is still
     * pending-add on the engine list. Opaque to callers; never cast outside the bridge. */
    public Object podObjectOrNull(int seatId) {
        Flight flight = flights.get(seatId);
        return flight == null ? null : flight.pod;
    }

    /** L4 S1 as re-shaped: the serve lane notes the pod's wire id =
     * the SEAT's id S (the seat rides it), for the XFER frames' linkage. */
    public void notePodWireId(int seatId, long podWireId) {
        Flight flight = flights.get(seatId);
        if (flight != null) {
            flight.podWireId = podWireId;
        }
    }

    /** The pod's wire id for ANY flight phase (RETURNING included — the
     * seat is aboard, the id is S, never a consumed row's residue); 0 = no flight/pre-note. */
    public long podWireIdOrZero(int seatId) {
        Flight flight = flights.get(seatId);
        return flight == null ? 0L : flight.podWireId;
    }

    /** The serve lane notes the source's FRESH wire id F at BEGIN. */
    public void noteSourceWireId(int seatId, long sourceWireId) {
        Flight flight = flights.get(seatId);
        if (flight != null) {
            flight.sourceWireId = sourceWireId;
        }
    }

    /** F for the seat's flight (0 = no flight / pod-body resume). Read BEFORE any verb that
     * may remove the flight ({@link #abortToReturning}, {@link #despawnFlight}). */
    public long sourceWireIdOrZero(int seatId) {
        Flight flight = flights.get(seatId);
        return flight == null ? 0L : flight.sourceWireId;
    }

    /** The flight's home body (null = none / pod-body resume) — the dock-back's target. */
    public Object sourceShipOrNull(int seatId) {
        Flight flight = flights.get(seatId);
        return flight == null ? null : flight.source;
    }

    /**
     * Mint the seat's flight (P3 drain-owned call: a row with no pod begins one). A
     * RETURNING/RETURNED straggler is RETARGETED in place (the seat is aboard — one body per
     * seat, never a despawn); a source that is itself a live pod body RESUMES in place
     * ({@link Port#toFlight}, no spawn); otherwise a fresh pod spawns from the source.
     * DENIED = fail-closed (no port / unresolvable hulls / spawner or flip deny) — the caller
     * aborts the arbiter row LOUD; nothing mutated.
     */
    public Begin spawnFlight(int seatId, String sourceStable, long targetWireId,
            String targetStable, long tick) {
        if (port == null) {
            System.out.println("npeer-serve pod_spawn_unavailable seat=" + seatId
                    + " round=" + round + " tick=" + tick);
            return Begin.DENIED;
        }
        if (unflipped.containsKey(seatId)) {
            // The seat's body is mid-flip-retry — its shape is unknown; a spawn or a
            // toFlight from it now would be the duplicate/undefined body. Fail-closed.
            System.out.println("npeer-serve pod_spawn_flip_pending seat=" + seatId
                    + " round=" + round + " tick=" + tick);
            return Begin.DENIED;
        }
        Object target = targetStable == null ? null : port.resolveShipOrNull(targetStable);
        Flight existing = flights.get(seatId);
        if (existing != null) {
            if (existing.phase == Phase.FLYING || existing.phase == Phase.DOCKED) {
                return Begin.ALREADY_FLYING;
            }
            if (target == null) {
                System.out.println("npeer-serve pod_spawn_unresolved seat=" + seatId
                        + " source=true target=false round=" + round + " tick=" + tick);
                return Begin.DENIED;
            }
            // A RETURNED (landed-home) or mid-landing straggler lifts off first.
            if ((existing.phase == Phase.RETURNED || port.isLanding(existing.pod))
                    && !port.abortLanding(existing.pod)) {
                System.out.println("npeer-serve pod_retarget_liftoff_denied seat=" + seatId
                        + " round=" + round + " tick=" + tick);
                return Begin.DENIED;
            }
            if (!port.retargetPod(existing.pod, target)
                    && !port.toFlight(existing.pod, target)) {
                System.out.println("npeer-serve pod_return_retarget_denied seat=" + seatId
                        + " round=" + round + " tick=" + tick);
                return Begin.DENIED;
            }
            existing.target = target;
            existing.targetWireId = targetWireId;
            existing.spawnTick = tick;
            existing.phase = Phase.FLYING;
            System.out.println("npeer-serve pod_return_retargeted seat=" + seatId
                    + " target_wire=" + targetWireId + " round=" + round + " tick=" + tick);
            return Begin.RETARGETED_RETURNING;
        }
        Object source = sourceStable == null ? null : port.resolveShipOrNull(sourceStable);
        if (source == null || target == null) {
            System.out.println("npeer-serve pod_spawn_unresolved seat=" + seatId
                    + " source=" + (source != null) + " target=" + (target != null)
                    + " round=" + round + " tick=" + tick);
            return Begin.DENIED;
        }
        if (port.isPodBody(source)) {
            // The seat's hull IS a stranded pod — it flies again as itself.
            if (!port.toFlight(source, target)) {
                System.out.println("npeer-serve pod_resume_denied seat=" + seatId
                        + " round=" + round + " tick=" + tick);
                return Begin.DENIED;
            }
            flights.put(seatId, new Flight(source, null, target, targetWireId, tick));
            System.out.println("npeer-serve pod_resume seat=" + seatId
                    + " target_wire=" + targetWireId + " round=" + round + " tick=" + tick);
            return Begin.RESUMED_POD_BODY;
        }
        Object pod = port.spawnPodOrNull(source, target);
        if (pod == null) {
            System.out.println("npeer-serve pod_spawn_denied seat=" + seatId
                    + " round=" + round + " tick=" + tick);
            return Begin.DENIED;
        }
        flights.put(seatId, new Flight(pod, source, target, targetWireId, tick));
        System.out.println("npeer-serve pod_spawn seat=" + seatId
                + " target_wire=" + targetWireId + " round=" + round + " tick=" + tick);
        podSpawns++;
        return Begin.SPAWNED;
    }

    /**
     * The once-per-tick advance (serve lane, BEFORE the drain). {@code arbiterFlights} is the
     * drain's own in-flight snapshot (arbiter stays truth: a row gone STRANDS the pod in
     * place, a row retargeted turns it — vanilla's setTo redirect, lifting off first if it
     * was landing/docked — and a turn that fails strands). Reports, per seat: TIMED_OUT (the
     * flight is left FLYING — the caller's abort end decides return-home vs strand, with the
     * real pod id on the frame), RETURNED (the return landing finished — the caller's
     * DOCK-BACK re-keys the seat onto the source and consumes the flight), STRANDED (the
     * flight ended in place; the caller ends the park). The common no-pod tick is one
     * empty-map check (frame-residency law).
     */
    public List<PodEvent> pump(long tick, Map<Integer, Long> arbiterFlights,
            Map<Long, String> stableByWireId) {
        retryFlips(tick);
        if (flights.isEmpty()) {
            return List.of();
        }
        List<PodEvent> events = new ArrayList<>();
        Iterator<Map.Entry<Integer, Flight>> it = flights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Flight> entry = it.next();
            int seatId = entry.getKey();
            Flight flight = entry.getValue();
            port.clampToMap(flight.pod);
            if (flight.phase == Phase.RETURNED) {
                continue; // holds for the caller's dock-back (same pump — never persists)
            }
            if (flight.phase == Phase.RETURNING) {
                LandingStep step = stepTowardLanding(flight, flight.source, seatId, tick);
                if (step == LandingStep.FAILED) {
                    strandInPlace(flight, seatId, tick, "return_lost", events);
                    it.remove();
                } else if (step == LandingStep.LANDED) {
                    flight.phase = Phase.RETURNED;
                    events.add(new PodEvent(seatId, EventKind.RETURNED, flight.sourceWireId));
                    System.out.println("npeer-serve pod_returned seat=" + seatId
                            + " round=" + round + " tick=" + tick);
                }
                continue;
            }
            Long row = arbiterFlights.get(seatId);
            if (row == null) {
                // Vacate/abort raced the row away without an abort end — the seat is aboard,
                // so the body strands in place (never an orphan despawn).
                strandInPlace(flight, seatId, tick, "orphaned", events);
                it.remove();
                continue;
            }
            if (row.longValue() != flight.targetWireId) {
                String freshStable = stableByWireId.get(row);
                Object fresh = freshStable == null ? null : port.resolveShipOrNull(freshStable);
                boolean turned = fresh != null
                        && ((flight.phase != Phase.DOCKED && !port.isLanding(flight.pod))
                                || port.abortLanding(flight.pod))
                        && port.retargetPod(flight.pod, fresh);
                if (!turned) {
                    strandInPlace(flight, seatId, tick, "retarget_lost", events);
                    it.remove();
                } else {
                    flight.target = fresh;
                    flight.targetWireId = row.longValue();
                    flight.phase = Phase.FLYING;
                    System.out.println("npeer-serve pod_retarget seat=" + seatId
                            + " fresh_wire=" + row + " round=" + round + " tick=" + tick);
                }
                continue;
            }
            if (flight.phase == Phase.DOCKED) {
                continue; // holds for the drain's commit consume
            }
            boolean landing = port.isLanding(flight.pod);
            if (timeoutTicks > 0 && !landing && tick - flight.spawnTick >= timeoutTicks) {
                // The timeout is OUR knob, vanilla has no give-up
                // timer — a LIVE target suspends it (re-base + keep flying, one line per
                // window); a dead target reports the seat and leaves the flight FLYING so the
                // caller's abort end turns it home (live source) or strands it.
                if (port.isAlive(flight.target)) {
                    flight.spawnTick = tick;
                    System.out.println("npeer-serve pod_timeout_suspended seat=" + seatId
                            + " round=" + round + " tick=" + tick);
                    continue;
                }
                events.add(new PodEvent(seatId, EventKind.TIMED_OUT, flight.sourceWireId));
                System.out.println("npeer-serve pod_timeout seat=" + seatId
                        + " round=" + round + " tick=" + tick);
                continue;
            }
            LandingStep step = stepTowardLanding(flight, flight.target, seatId, tick);
            if (step == LandingStep.FAILED) {
                strandInPlace(flight, seatId, tick, "landing_lost", events);
                it.remove();
            } else if (step == LandingStep.LANDED) {
                flight.phase = Phase.DOCKED;
                System.out.println("npeer-serve pod_docked seat=" + seatId
                        + " round=" + round + " tick=" + tick);
            }
        }
        return events;
    }

    /**
     * One landing-progression step toward {@code landOn}: begin inside {@link #DOCK_RANGE}
     * (vanilla's own !isLanding guard), then finish. FAILED = a port read/verb failed (the
     * caller strands); LANDED = the animation finished (the caller sets DOCKED/RETURNED).
     */
    private LandingStep stepTowardLanding(Flight flight, Object landOn, int seatId, long tick) {
        if (!port.isLanding(flight.pod)) {
            float dist = port.distanceOrNaN(flight.pod, landOn);
            if (Float.isNaN(dist)) {
                System.out.println("npeer-serve pod_lost seat=" + seatId
                        + " round=" + round + " tick=" + tick);
                return LandingStep.FAILED;
            }
            if (dist < DOCK_RANGE && !port.beginLanding(flight.pod, landOn)) {
                System.out.println("npeer-serve pod_landing_lost seat=" + seatId
                        + " round=" + round + " tick=" + tick);
                return LandingStep.FAILED;
            }
            return LandingStep.PROGRESS;
        }
        return port.isFinishedLanding(flight.pod) ? LandingStep.LANDED : LandingStep.PROGRESS;
    }

    /** The strand: the body flips pilotable where it is (pushed clear of whatever
     * it was landing on), the flight leaves the table (the CALLER removes the map entry),
     * the event carries F. A flip failure is LOUD but still a strand — the seat is aboard. */
    private void strandInPlace(Flight flight, int seatId, long tick, String reason,
            List<PodEvent> events) {
        Object landedOn = landedOnOrNull(flight);
        boolean flipped = port.toPilotable(flight.pod, landedOn);
        if (!flipped) {
            // The seat is aboard a body the engine would not flip — keep it for the
            // per-pump retry (never silent, never a second body).
            unflipped.put(seatId, new Unflipped(flight.pod, landedOn));
        }
        System.out.println("npeer-serve pod_stranded seat=" + seatId + " reason=" + reason
                + " flipped=" + flipped + " round=" + round + " tick=" + tick);
        if (events != null) {
            events.add(new PodEvent(seatId, EventKind.STRANDED, flight.sourceWireId));
        }
    }

    /**
     * The per-pump retry of every refused pilotable flip ({@link #unflipped}): the first
     * success ends the wait (the seat flies the pod, as any strand); the
     * {@link #FLIP_RETRY_LIMIT}-th refusal despawns the body — the seat-despawn observer's
     * mount loss then re-arms the fly-in (no lane call: the observer keys on the seat's wire
     * id, which the body carries). The common tick is one empty-map check.
     */
    private void retryFlips(long tick) {
        if (unflipped.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Unflipped>> it = unflipped.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Unflipped> entry = it.next();
            int seatId = entry.getKey();
            Unflipped wait = entry.getValue();
            wait.attempts++;
            if (port.toPilotable(wait.pod, wait.landedOn)) {
                it.remove();
                System.out.println("npeer-serve pod_flip_recovered seat=" + seatId
                        + " attempts=" + wait.attempts + " round=" + round + " tick=" + tick);
            } else if (wait.attempts >= FLIP_RETRY_LIMIT) {
                it.remove();
                port.despawnPod(wait.pod);
                System.out.println("npeer-serve pod_flip_abandoned seat=" + seatId
                        + " attempts=" + wait.attempts
                        + " body=despawned (observer mount-loss -> fly-in)"
                        + " round=" + round + " tick=" + tick);
            }
        }
    }

    /** The body the pod is landing on / landed on (its pose is INSIDE that hull's bounds),
     * or null while free-flying. */
    private Object landedOnOrNull(Flight flight) {
        if (flight.phase == Phase.RETURNING || flight.phase == Phase.RETURNED) {
            return flight.phase == Phase.RETURNED || port.isLanding(flight.pod)
                    ? flight.source : null;
        }
        return flight.phase == Phase.DOCKED || port.isLanding(flight.pod) ? flight.target : null;
    }

    /** The dock consume (COMMIT onto the target, or the DOCK-BACK onto the source): the seat
     * was re-keyed off the pod by the caller FIRST — only then does the body leave the world. */
    public void despawnFlight(int seatId, long tick) {
        Flight flight = flights.remove(seatId);
        if (flight != null) {
            port.despawnPod(flight.pod);
            System.out.println("npeer-serve pod_despawn seat=" + seatId
                    + " round=" + round + " tick=" + tick);
        }
    }

    /** The caller's forced strand — the DOCK-BACK's re-key denied (the
     * source died during the return landing), so the flight ends in place. False = no flight. */
    public boolean strandFlight(int seatId, long tick) {
        Flight flight = flights.remove(seatId);
        if (flight == null) {
            return false;
        }
        strandInPlace(flight, seatId, tick, "dock_back_denied", null);
        return true;
    }

    /**
     * The soft abort (P3 race losers, P4 target-death sweeps, timeouts, return-home): the
     * pod turns home toward a LIVE source and rides RETURNING (lifting off first if it was
     * landing/docked — the public {@code abortLanding}); any other end STRANDS in
     * place. {@code allowReturnHome} false (PILOT_LEFT / disconnected — nobody to fly home)
     * strands outright.
     *
     * <p>The STRAND LAW: a flight with
     * no good home — source dead, gone, or never there (a pod-body resume) — ends with the
     * seat flying the pod from where it is. A live TARGET no longer rescues a dead-source
     * abort (the seat is aboard the pod, and a pod flying to a
     * target whose row was just aborted has nowhere to dock).
     */
    public AbortEnd abortToReturning(int seatId, long tick, boolean allowReturnHome) {
        Flight flight = flights.get(seatId);
        if (flight == null) {
            return AbortEnd.NONE;
        }
        if (flight.phase == Phase.RETURNING || flight.phase == Phase.RETURNED) {
            return AbortEnd.RETURNING;
        }
        if (!allowReturnHome || flight.source == null || !port.isAlive(flight.source)) {
            flights.remove(seatId);
            strandInPlace(flight, seatId, tick,
                    !allowReturnHome ? "no_pilot" : flight.source == null ? "no_home"
                            : "source_dead", null);
            return AbortEnd.STRANDED;
        }
        boolean turned = ((flight.phase != Phase.DOCKED && !port.isLanding(flight.pod))
                || port.abortLanding(flight.pod))
                && port.retargetPod(flight.pod, flight.source);
        if (!turned) {
            flights.remove(seatId);
            strandInPlace(flight, seatId, tick, "abort_turn_lost", null);
            return AbortEnd.STRANDED;
        }
        flight.phase = Phase.RETURNING;
        System.out.println("npeer-serve pod_abort_returning seat=" + seatId
                + " round=" + round + " tick=" + tick);
        return AbortEnd.RETURNING;
    }

    /** Round wrap (P4 wires the call site): every pod leaves the world with the round. */
    public void clearRound() {
        for (Flight flight : flights.values()) {
            port.despawnPod(flight.pod);
        }
        for (Unflipped wait : unflipped.values()) {
            port.despawnPod(wait.pod); // A flip-wait body leaves with the round too
        }
        int cleared = flights.size() + unflipped.size();
        flights.clear();
        unflipped.clear();
        if (cleared > 0) {
            System.out.println("npeer-serve pod_round_clear count=" + cleared
                    + " round=" + round);
        }
    }
}
