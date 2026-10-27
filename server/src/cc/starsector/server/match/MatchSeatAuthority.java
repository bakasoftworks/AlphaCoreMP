package cc.starsector.server.match;

import cc.starsector.server.protocol.SeatRole;
import cc.starsector.server.protocol.SeatStateCause;
import cc.starsector.server.protocol.SeatStateEvent;
import cc.starsector.server.runtime.AdminBanCommand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Server-authoritative seat occupancy ledger over a {@link MatchDescriptor} — demo-program R8b.
 *
 * <p>R8a landed the stateless gate-level seat-token check in
 * {@link cc.starsector.server.client.SnapshotClientAdmissionGate}: it sees ONE handshake and so
 * can only reject a blank ({@code seat_token_required}) or unminted ({@code seat_token_unknown})
 * token. It explicitly deferred two <em>cross-connection</em> concerns to R8b — both of which need
 * state the stateless gate does not hold:
 * <ul>
 *   <li><b>duplicate-seat policy</b> — a token may seat AT MOST ONE live connection; a second
 *       connection presenting an already-seated token is {@link Status#ALREADY_SEATED} and the
 *       existing occupant is unchanged;</li>
 *   <li><b>seed the authority arbiter FROM the token's seat</b> — the seat token binds the ship a
 *       connection may command ({@link MatchDescriptor.Seat#shipId()}), replacing the
 *       client-asserted {@code ownedShipId} (the guessable "first-to-connect wins the seat" path
 *       the seat-credential idea closes). {@link #authorizedShipId(String)} is that source: a
 *       {@code ConcurrentCommandAuthorityArbiter.ClientAuthorityIdentity} built from it can only
 *       command the credential's own ship.</li>
 * </ul>
 *
 * <p>This ledger is the stateful half R8a deferred. It is also the substrate the L2 dynamic-roster
 * {@code SEAT_CLAIM}/{@code SEAT_VACATE} events and the death-contract RESEAT consume —
 * {@link #vacate(String)} releases a seat so a dropped or dead occupant's slot can be re-claimed
 * (re-seat is NOT a connection event).
 *
 * <p>Game-free and self-contained: it indexes only the descriptor's already-distinct seat tokens
 * (the {@link MatchDescriptor} constructor rejects duplicate tokens) and tracks live occupancy in
 * memory. {@link #authorizedShipId(String)} / {@link #isMinted(String)} are immutable descriptor
 * lookups; {@link #claim(String)} / {@link #vacate(String)} / {@link #isSeated(String)} /
 * {@link #seatedCount()} touch the occupancy set and are {@code synchronized} so a future
 * concurrent admission accept loop stays correct (today's runner accepts sequentially).
 *
 * <p>Server-only, like {@link MatchDescriptors}: it holds live connection state and is NOT bundled
 * into the client mod jar.
 *
 * <p><b>The D4.4 seat machine (model frozen):</b> beside
 * the occupancy axis above, an ARMED authority ({@link #armSeatMachine(MatchRespawnPolicy)} —
 * armed by a serve lane with respawn enabled: N-peer always, legacy iff
 * {@code cc.match.respawnPolicy != disabled}; unarmed, every legacy surface is
 * byte-identical) tracks a per-seat MOUNT axis
 * (hull exists or not) plus the never-mounted-this-round and dead-latch flags. The four states read
 * off the two axes: (vacant, no hull) = ABSENT, (occupied, hull) = ACTIVE, (vacant, hull) = AFK
 * coast, (occupied, no hull) = PENDING fly-in or dead-latched. Transitions append pending records
 * thread-safely (T1 inside {@link #claim(String)}; T2 via the cause-aware
 * {@link #vacate(String, SeatStateCause)}; T3/T4 via the sim-thread mount mutators); the sim
 * thread {@link #drainSeatEvents(long) drains} once per tick — the single writer that stamps
 * simTick and mints seatSeq — resolving each record to the seat's CURRENT full state (idempotent,
 * replay-safe; a T1 claim whose fly-in lands the same tick simply emits its records with the
 * settled post-fly-in state). The ONE reconcile rule unifying the T1 fly-in and the T4 remount is
 * {@link #seatsDueForFlyIn(long)}.
 */
public final class MatchSeatAuthority {

    /** The outcome kind of a {@link #claim(String)}. */
    public enum Status {
        /** The token is minted and was free — the connection is now seated. */
        SEATED,
        /** The token is not one of the descriptor's minted seat tokens. */
        UNKNOWN_TOKEN,
        /** The token is minted but already holds a live seat (duplicate-seat policy). */
        ALREADY_SEATED
    }

    /**
     * The result of a {@link #claim(String)}: the {@link Status} plus, for a known token
     * ({@code SEATED} or {@code ALREADY_SEATED}), the bound seat fields. For
     * {@link Status#UNKNOWN_TOKEN} the seat fields are sentinels and must not be read — only
     * {@link #status()} and {@link #token()} are meaningful.
     */
    public record Seating(Status status, int seatId, SeatRole role, int side, long shipId, String token) {
        public Seating {
            status = Objects.requireNonNull(status, "status");
            token = Objects.requireNonNull(token, "token");
            if (status != Status.UNKNOWN_TOKEN) {
                if (seatId < 1) {
                    throw new IllegalArgumentException("seated seat.id must be >= 1, got: " + seatId);
                }
                if (role == null) {
                    throw new IllegalArgumentException("seated seat.role must not be null");
                }
                if (side != 0 && side != 1) {
                    throw new IllegalArgumentException("seated seat.side must be 0 or 1, got: " + side);
                }
                if (shipId < 1) {
                    throw new IllegalArgumentException("seated seat.ship must be >= 1, got: " + shipId);
                }
            }
        }

        /** True iff this claim newly seated the connection (the only admit-onward status). */
        public boolean seated() {
            return status == Status.SEATED;
        }
    }

    /** Per-seat runtime mount-axis state — mutated only while the seat machine is armed. */
    private static final class SeatRuntime {
        final MatchDescriptor.Seat seat;
        boolean mounted;
        boolean neverMountedThisRound = true;
        boolean deadLatched;
        /** The combat-ready deploy gate — per-seat-per-MATCH, see {@link #recordCombatReady}. */
        boolean combatReady;
        /** A fresh claim's variant pick has not yet
         * been applied by the door's post-spool apply — {@link #seatsDueForFlyIn} refuses the
         * seat while set (the tenure gate). Armed by {@link #claim(String, boolean)} (the door's
         * fresh claim; {@link #markPickApplyPending} = the rows' seam), cleared by
         * {@link #clearPickApplyPending} from the door's {@code finally} (fail-open) and by the
         * seat's release in {@link #vacate(String, SeatStateCause)}. */
        boolean pickApplyPending;
        long lossTick = -1L;
        /** Tick a voluntary dismount armed a despawn-echo expectation for,
         * -1 = none — see {@link #recordVoluntaryDismount}/{@link #consumeDismountDespawnEcho}. */
        long dismountEchoTick = -1L;
        /** W3-FIX: tick+wire-id a PARKED takeover freed this seat's fixed wire id at,
         * -1 = none — the commit's registry rekey releases the displaced seat's own id, which
         * surfaces as a same-tick despawn AFTER the loss was already recorded (the CTSL
         * pre-step). Parallel to {@code dismountEchoTick}, id-verified — see
         * {@link #armTakeoverDespawnEcho}/{@link #consumeTakeoverDespawnEcho}. */
        long takeoverEchoTick = -1L;
        long takeoverEchoWireId = -1L;
        /** W5 grace machine (mutated only while {@link #armGraceMachine} has run):
         * the AFK sub-state, its sim-tick deadline (-1 = none), and the entry mark a TRUE
         * vacate leaves for the sim thread's {@link #pumpGraceTransitions} to resolve (the
         * deadline is minted ON the sim thread from sim ticks — sim-tick clock DECISION). */
        SeatGraceState graceState = SeatGraceState.NONE;
        long graceDeadlineTick = -1L;
        boolean graceEntryPending;

        SeatRuntime(MatchDescriptor.Seat seat) {
            this.seat = seat;
        }
    }

    /**
     * W5 reconnect-grace sub-states of AFK (vacant + mounted hull):
     * {@code NONE} = no grace episode; {@code GRACE} = a cautious-AI window is running toward
     * its sim-tick deadline; {@code PARKED} = the window expired — the seat is the W3
     * command-transfer candidate ({@link #abandonedEligible(int)}). Server-internal, ZERO wire.
     */
    public enum SeatGraceState { NONE, GRACE, PARKED }

    /**
     * What a grace transition asks the engine lane to do — leg 2's {@code SeatGraceLane} drains
     * these on the sim thread via {@link #pumpGraceTransitions(long)} and performs the engine
     * verbs: {@code ENTER_GRACE} = save personality, set cautious, attach AI, re-arm
     * autofire; {@code PARK_STAY_AI} = keep the AI, mark only; {@code PARK_DRIFT} = detach AI +
     * restore personality; {@code RECLAIM} = re-admit in GRACE/PARKED — explicit
     * {@code setShipAI(null)} + personality restore + suppress-latch reset; {@code CLEAR_MOUNT_LOST}
     * = the graced hull died/departed — drop any per-seat grace bookkeeping (nothing to detach:
     * the AI died with the hull). {@code VACATE_BANNED}
     * = a ban vacated the seat and PARKED it at once (never a reconnect hold) — the lane drops the
     * staged frame and leaves the hull in its park end state per policy.
     */
    public enum SeatGraceTransitionKind {
        ENTER_GRACE, PARK_STAY_AI, PARK_DRIFT, RECLAIM, CLEAR_MOUNT_LOST, VACATE_BANNED
    }

    /** One grace state-machine transition, drained in chronological order by the sim thread. */
    public record SeatGraceTransition(int seatId, SeatGraceTransitionKind kind) {
    }

    /** A transition awaiting the sim thread's drain — full state resolves at emission. */
    private record PendingTransition(int seatId, SeatStateCause cause) {
    }

    /** The descriptor this authority was built from: the password door's served match document is prepared
     *  from it at open. */
    private final MatchDescriptor descriptor;
    private final Map<String, MatchDescriptor.Seat> seatsByToken;
    private final Set<String> occupied = new HashSet<String>();
    private final TreeMap<Integer, SeatRuntime> runtimeBySeatId = new TreeMap<Integer, SeatRuntime>();
    private final List<PendingTransition> pending = new ArrayList<PendingTransition>();
    private final List<SeatGraceTransition> pendingGrace = new ArrayList<SeatGraceTransition>();
    private MatchRespawnPolicy respawnPolicy;
    private MatchGracePolicy gracePolicy;
    private long nextSeatSeq = 1L;
    /** Pending records the drain folded away under latest-wins-per-seat. */
    private long seatRecordsCoalesced;

    public MatchSeatAuthority(MatchDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        Map<String, MatchDescriptor.Seat> byToken = new HashMap<String, MatchDescriptor.Seat>();
        for (MatchDescriptor.Seat seat : descriptor.seats()) {
            // A hull id inside the OBSERVER HANDLE band
            // would let a /ban or /mute handle alias a pilot — refused (seat id only, no text).
            if (seat.shipId() >= AdminBanCommand.OBSERVER_HANDLE_BASE) {
                throw new IllegalArgumentException("seat " + seat.seatId()
                        + " ship id is inside the observer handle band (>= "
                        + AdminBanCommand.OBSERVER_HANDLE_BASE + ")");
            }
            // The descriptor constructor already rejects duplicate tokens, so this never collides.
            byToken.put(seat.seatToken(), seat);
            runtimeBySeatId.put(seat.seatId(), new SeatRuntime(seat));
        }
        this.seatsByToken = byToken;
        this.descriptor = descriptor;
    }

    /** The descriptor this authority was built from (never null: the one constructor requires it). */
    public MatchDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Claim the seat bound to {@code token}. A minted, free token is {@link Status#SEATED} and the
     * seat is now occupied; a minted, occupied token is {@link Status#ALREADY_SEATED} (the
     * duplicate-seat policy — the occupant is unchanged); an unminted token is
     * {@link Status#UNKNOWN_TOKEN}. {@code token} must be non-blank (the gate rejects a blank token
     * with {@code seat_token_required} BEFORE the ledger is consulted).
     */
    public synchronized Seating claim(String token) {
        return claim(token, false);
    }

    /**
     * The door's claim — {@code armTenureGate} = the door's
     * FRESH-claim verdict (a NEW tenure whose post-spool variant-pick apply the seat's first
     * fly-in must wait for). The gate arms in THIS monitor step, together with the occupancy add:
     * between {@code SeatAdmission.authorize} and a later door-side mark a sim frame could fly
     * the re-claimed seat with the previous tenure's pick. A
     * plain {@link #claim(String)} never arms it (rigs, rows, the legacy TCP door). Cleared by the
     * door's provisioning {@code finally} ({@link #clearPickApplyPending}, fail-open) and by
     * {@link #vacate(String, SeatStateCause)} (the table-full unwind / a BYE landing mid-spool
     * never strands it); an unarmed seat machine has no gate.
     */
    public synchronized Seating claim(String token, boolean armTenureGate) {
        String t = requireToken(token);
        MatchDescriptor.Seat seat = seatsByToken.get(t);
        if (seat == null) {
            return new Seating(Status.UNKNOWN_TOKEN, 0, null, 0, 0L, t);
        }
        Status status = occupied.add(t) ? Status.SEATED : Status.ALREADY_SEATED;
        if (respawnPolicy != null && status == Status.SEATED) {
            // T1 CLAIM — recorded here so the door path (SeatAdmission.authorize -> claim) needs
            // no change; the sim thread's drain resolves the full state (a same-tick fly-in has
            // already settled by then).
            pending.add(new PendingTransition(seat.seatId(), SeatStateCause.CLAIM));
            if (armTenureGate) {
                runtimeBySeatId.get(seat.seatId()).pickApplyPending = true;
            }
        }
        if (gracePolicy != null && status == Status.SEATED) {
            // W5 RECLAIM: a re-admit in GRACE or PARKED ends the grace episode —
            // the queued record tells leg 2's engine lane to detach the AI + restore the
            // personality. A re-claim BEFORE the pump resolved the entry simply cancels it
            // (no episode began, nothing engine-side to undo).
            SeatRuntime runtime = runtimeBySeatId.get(seat.seatId());
            runtime.graceEntryPending = false;
            if (runtime.graceState != SeatGraceState.NONE) {
                runtime.graceState = SeatGraceState.NONE;
                runtime.graceDeadlineTick = -1L;
                pendingGrace.add(new SeatGraceTransition(seat.seatId(), SeatGraceTransitionKind.RECLAIM));
            }
        }
        return new Seating(status, seat.seatId(), seat.role(), seat.side(), seat.shipId(), t);
    }

    /**
     * Release the seat bound to {@code token} so it can be re-claimed (L2 {@code SEAT_VACATE} /
     * death-contract RESEAT). Returns whether a live seat was actually released — idempotent: a
     * second vacate, or a vacate of an unminted/unseated token, returns {@code false}.
     *
     * <p>Fail-closed while the seat machine is armed: every occupancy transition must reach the
     * SEAT event stream, so an armed authority THROWS here — N-peer door sites use the cause-aware
     * {@link #vacate(String, SeatStateCause)}.
     */
    public synchronized boolean vacate(String token) {
        if (respawnPolicy != null) {
            throw new IllegalStateException(
                    "the armed seat machine requires the cause-aware vacate(token, cause) so every"
                            + " occupancy transition reaches the SEAT event stream");
        }
        return occupied.remove(requireToken(token));
    }

    /**
     * The cause-aware T2 vacate — same release semantics as {@link #vacate(String)}, plus (when
     * the seat machine is armed) a pending SEAT transition record carrying which door site fired
     * ({@code VACATE_BYE} / {@code VACATE_SILENCE} / {@code VACATE_DISPLACED}). The mount axis is
     * untouched: a mounted seat becomes AFK (hull coasts), an unmounted one returns to ABSENT.
     * Legal unarmed (degrades to the plain vacate) so call sites can be uniform.
     */
    public synchronized boolean vacate(String token, SeatStateCause cause) {
        if (cause != SeatStateCause.VACATE_BYE && cause != SeatStateCause.VACATE_SILENCE
                && cause != SeatStateCause.VACATE_DISPLACED) {
            throw new IllegalArgumentException(
                    "vacate cause must be VACATE_BYE | VACATE_SILENCE | VACATE_DISPLACED, got: " + cause);
        }
        String t = requireToken(token);
        boolean released = occupied.remove(t);
        if (released && respawnPolicy != null) {
            pending.add(new PendingTransition(seatsByToken.get(t).seatId(), cause));
            // A released seat drops its tenure gate — the door's table-full
            // unwind and a BYE that lands mid-spool never strand a pending mark.
            runtimeBySeatId.get(seatsByToken.get(t).seatId()).pickApplyPending = false;
        }
        if (released && gracePolicy != null && !gracePolicy.off()
                && cause != SeatStateCause.VACATE_DISPLACED) {
            // W5 GRACE entry mark: a TRUE vacate (BYE/silence — DISPLACED is a
            // takeover, excluded) of a seat with a mounted plain hull. A module-riding or dead
            // seat reads unmounted here and stays NONE (modules keep dismount-on-disconnect —
            // the restored station AI IS the module's grace). The sim thread's next
            // pumpGraceTransitions resolves the mark into GRACE and mints the deadline.
            SeatRuntime runtime = runtimeBySeatId.get(seatsByToken.get(t).seatId());
            if (runtime.mounted && runtime.graceState == SeatGraceState.NONE) {
                runtime.graceEntryPending = true;
            }
        }
        return released;
    }

    /**
     * (a ban vacates the seat
     * at once, never a reconnect-grace hold): the BAN vacate. The release, the SEAT-stream record
     * and the tenure-gate drop are exactly {@link #vacate(String, SeatStateCause)} with
     * {@code VACATE_SILENCE} (the wire cause is unchanged — the SEAT stream stays byte-identical).
     * Then, with grace ON, the entry mark is cancelled and a mounted seat goes straight to PARKED
     * with ONE {@code VACATE_BANNED} record — from NONE (a live pilot), from GRACE (the seat was
     * already vacant and holding: nothing is released, the hold still ends) or from a same-frame
     * entry mark (no ENTER_GRACE is ever minted). PARKED makes {@link #abandonedEligible} TRUE at
     * once (the W3 transfer fodder). An unmounted seat mints no grace record, as {@code vacate}.
     * Legal unarmed (plain release), and with grace OFF or unarmed there is no grace record.
     */
    public synchronized boolean vacateBanned(String token) {
        boolean released = vacate(token, SeatStateCause.VACATE_SILENCE);
        MatchDescriptor.Seat seat = seatsByToken.get(requireToken(token));
        if (seat != null && gracePolicy != null && !gracePolicy.off()) {
            SeatRuntime runtime = runtimeBySeatId.get(seat.seatId());
            runtime.graceEntryPending = false;
            if (runtime.mounted && runtime.graceState != SeatGraceState.PARKED) {
                runtime.graceState = SeatGraceState.PARKED;
                runtime.graceDeadlineTick = -1L;
                pendingGrace.add(new SeatGraceTransition(seat.seatId(), SeatGraceTransitionKind.VACATE_BANNED));
            }
        }
        return released;
    }

    /** Whether {@code token} currently holds a live seat. */
    public synchronized boolean isSeated(String token) {
        return occupied.contains(requireToken(token));
    }

    /** The number of seats currently occupied by a live connection. */
    public synchronized int seatedCount() {
        return occupied.size();
    }

    /** Whether {@code token} is one of the descriptor's minted seat tokens (occupancy aside). */
    public boolean isMinted(String token) {
        return seatsByToken.containsKey(requireToken(token));
    }

    /**
     * The descriptor's minted seat tokens (occupancy aside) — the set the stateless R8a/R8c
     * {@link cc.starsector.server.client.SnapshotClientAdmissionGate} enforces as
     * {@code enforcedSeatTokens} (blank → {@code seat_token_required}, not-in-set →
     * {@code seat_token_unknown}). The authority owns the duplicate-seat policy + arbiter seed on
     * top of that stateless membership check; this accessor lets the runner configure the gate
     * from the same source the ledger uses, so the two never disagree on what is minted.
     */
    public Set<String> mintedTokens() {
        return Set.copyOf(seatsByToken.keySet());
    }

    /**
     * The ship id the token's seat is bound to — the authority-arbiter seed that replaces a
     * client-asserted {@code ownedShipId}. Throws if {@code token} is not minted by this match
     * (callers reject unminted tokens at admission; this is the post-admission authorization
     * source, so a non-minted token here is a caller bug, not an admission decision).
     */
    public long authorizedShipId(String token) {
        MatchDescriptor.Seat seat = seatsByToken.get(requireToken(token));
        if (seat == null) {
            throw new IllegalArgumentException("seat token is not minted by this match: " + token);
        }
        return seat.shipId();
    }

    /**
     * The seat id the token's seat is bound to — the N-peer door's session-handle seed
     * ({@code SeatAdmission.authorize} returns only clientId+shipId, and the per-session handle
     * carries the seatId the seat machine keys on). Same posture as {@link #authorizedShipId}:
     * post-admission authorization source, so an unminted token here is a caller bug and throws.
     */
    public int authorizedSeatId(String token) {
        MatchDescriptor.Seat seat = seatsByToken.get(requireToken(token));
        if (seat == null) {
            throw new IllegalArgumentException("seat token is not minted by this match: " + token);
        }
        return seat.seatId();
    }

    // ------------------------------------------------------------------------------------------
    // The D4.4 seat machine (mount axis). Everything below requires an armed authority and is
    // fail-closed unarmed; unarmed, the legacy occupancy surface above is byte-identical.
    // ------------------------------------------------------------------------------------------

    /**
     * Arm the seat machine with the match's respawn policy (the carrier; default =
     * {@code disabled}). Each serve lane arms exactly once before opening its door — N-peer
     * always, the legacy lane iff the policy enables respawn (it then drain-drops the
     * SEAT records, D9 wire-silent). Arming twice is a wiring bug and throws.
     */
    public synchronized void armSeatMachine(MatchRespawnPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("respawn policy must not be null");
        }
        if (respawnPolicy != null) {
            throw new IllegalStateException("seat machine already armed");
        }
        respawnPolicy = policy;
    }

    /** Whether {@link #armSeatMachine(MatchRespawnPolicy)} has run. */
    public synchronized boolean seatMachineArmed() {
        return respawnPolicy != null;
    }

    /**
     * Record a hull arriving on {@code seatId} (sim thread; the fly-in completing, or the round-
     * start deploy of a mid-round claim). NONE→HULL only — mounting a mounted seat throws. The
     * cause is inferred from the round history: first mount of the round = the T1 CLAIM arc,
     * any later mount = T4 REMOUNT.
     */
    public synchronized void recordMountEstablished(int seatId) {
        SeatRuntime runtime = requireArmedRuntime(seatId);
        if (runtime.mounted) {
            throw new IllegalStateException(
                    "seat " + seatId + " already has a mounted hull (mount-established is NONE->HULL)");
        }
        SeatStateCause cause =
                runtime.neverMountedThisRound ? SeatStateCause.CLAIM : SeatStateCause.REMOUNT;
        runtime.mounted = true;
        runtime.neverMountedThisRound = false;
        runtime.lossTick = -1L;
        // A refield closes any open dismount-echo window — the seat's wire id
        // survived (an INSTANT-policy same-tick refield keeps it in the roster, so the echo never
        // comes), and any LATER despawn of that id is a real loss again. The same law covers
        // the takeover echo — once the seat has a new hull, any despawn is a real loss again.
        runtime.dismountEchoTick = -1L;
        runtime.takeoverEchoTick = -1L;
        runtime.takeoverEchoWireId = -1L;
        pending.add(new PendingTransition(seatId, cause));
    }

    /**
     * Record the T3 mount-loss for {@code seatId} at {@code simTick} — hull death or map-edge
     * departure, as classified by the seat-despawn observer. HULL→NONE; occupancy untouched.
     * Under the {@code disabled} policy this latches the seat dead-for-the-round (as
     * amended: claims still admit, the round reset is the respawn).
     */
    public synchronized void recordMountLoss(int seatId, SeatStateCause cause, long simTick) {
        if (cause != SeatStateCause.HULL_DEATH && cause != SeatStateCause.HULL_DEPARTED) {
            throw new IllegalArgumentException(
                    "mount-loss cause must be HULL_DEATH | HULL_DEPARTED, got: " + cause);
        }
        if (simTick < 0L) {
            throw new IllegalArgumentException("mount-loss simTick must be >= 0, got: " + simTick);
        }
        SeatRuntime runtime = requireArmedRuntime(seatId);
        if (!runtime.mounted) {
            throw new IllegalStateException("seat " + seatId + " has no mounted hull to lose");
        }
        runtime.mounted = false;
        runtime.lossTick = simTick;
        if (respawnPolicy.disabled()) {
            runtime.deadLatched = true;
        }
        if (gracePolicy != null) {
            // W5: losing the hull ends any grace episode — the AI died with the hull, so the
            // engine lane only drops its per-seat bookkeeping (CLEAR_MOUNT_LOST, no detach).
            // The normal loss path above (dead-latch under disabled) is untouched: a vacant
            // hull dies with the round.
            runtime.graceEntryPending = false;
            if (runtime.graceState != SeatGraceState.NONE) {
                runtime.graceState = SeatGraceState.NONE;
                runtime.graceDeadlineTick = -1L;
                pendingGrace.add(new SeatGraceTransition(seatId, SeatGraceTransitionKind.CLEAR_MOUNT_LOST));
            }
        }
        pending.add(new PendingTransition(seatId, cause));
    }

    /**
     * The VOLUNTARY-dismount mount-loss (the {@code /module off} drain and
     * the vacate-while-mounted funnel) — {@link #recordMountLoss} with HULL_DEPARTED, plus it
     * arms a one-shot expectation that THIS tick's despawn pass will see the seat's wire id
     * leave the roster (the module re-keys to a fresh above-band id, so the old id's
     * disappearance is a bookkeeping ECHO of a loss already fully recorded — live map included,
     * {@code dismountStationModule} vacates it). Without the expectation the despawn pass
     * re-enters {@code recordMountLoss} on an unmounted seat and the fail-closed throw kills the
     * serve — a recorded serve crash.
     */
    public synchronized void recordVoluntaryDismount(int seatId, long simTick) {
        recordMountLoss(seatId, SeatStateCause.HULL_DEPARTED, simTick);
        requireArmedRuntime(seatId).dismountEchoTick = simTick;
    }

    /**
     * Consume the seat's armed dismount-despawn echo, if any (sim thread, the despawn pass's
     * FIRST consult for a seat-mapped despawn): {@code true} exactly when an echo is armed for
     * {@code nowTick} — the caller then SKIPS the whole seat-loss block (everything it does was
     * already done by the dismount drain). An echo armed for an EARLIER tick is stale — the
     * expected disappearance never surfaced (an INSTANT-policy same-tick refield kept the wire
     * id in the roster; {@link #recordMountEstablished} also clears it for exactly that case) —
     * so it disarms and returns {@code false}: the caller processes the despawn as the real
     * loss it is. Never {@code true} twice: consult-once = consume.
     */
    public synchronized boolean consumeDismountDespawnEcho(int seatId, long nowTick) {
        SeatRuntime runtime = requireArmedRuntime(seatId);
        if (runtime.dismountEchoTick < 0L) {
            return false;
        }
        boolean echo = runtime.dismountEchoTick == nowTick;
        runtime.dismountEchoTick = -1L;
        return echo;
    }

    /**
     * W3-FIX: arm the PARKED-takeover despawn echo — the transfer drain's
     * commit already recorded the displaced seat's mount loss (the CTSL pre-step), then the
     * registry rekey FREES that seat's own fixed wire id, which the despawn pass sees leave the
     * snapshot the same tick. Without this expectation the pass re-enters
     * {@link #recordMountLoss} on the already-unmounted seat and the fail-closed throw kills
     * the serve (the flip-watch crash). Arm-only — the loss is NOT re-recorded
     * here; {@code freedWireId} is the parked seat's released id, verified at consume.
     */
    public synchronized void armTakeoverDespawnEcho(int seatId, long freedWireId, long simTick) {
        SeatRuntime runtime = requireArmedRuntime(seatId);
        runtime.takeoverEchoTick = simTick;
        runtime.takeoverEchoWireId = freedWireId;
    }

    /**
     * Consume the seat's armed takeover-despawn echo, if any (sim thread, consulted beside
     * {@link #consumeDismountDespawnEcho} for a seat-mapped despawn): {@code true} exactly when
     * an echo is armed for {@code nowTick} AND {@code freedWireId} matches — the caller then
     * skips the seat-loss block (the takeover pre-step already recorded it), and the consume
     * disarms (never {@code true} twice). Disarm rides ONLY the positive match or a passed
     * tick (0335-F2): a SAME-TICK id-mismatch keeps the echo armed — the (c-race) ordering can
     * deliver another same-tick despawn on this seat's map BEFORE the freed-id despawn, and
     * burning the echo there re-opens the serve-death (the freed id then falls through to
     * {@link #recordMountLoss} on the unmounted seat = the fail-closed throw). The mismatched
     * despawn itself still returns {@code false}: it is a real loss, processed normally. An
     * echo armed for an EARLIER tick is stale — disarm and {@code false} (the echo never
     * outlives its tick, so a held echo cannot swallow a later real loss).
     */
    public synchronized boolean consumeTakeoverDespawnEcho(int seatId, long freedWireId,
            long nowTick) {
        SeatRuntime runtime = requireArmedRuntime(seatId);
        if (runtime.takeoverEchoTick < 0L) {
            return false;
        }
        if (runtime.takeoverEchoTick == nowTick && runtime.takeoverEchoWireId == freedWireId) {
            runtime.takeoverEchoTick = -1L;
            runtime.takeoverEchoWireId = -1L;
            return true;
        }
        if (runtime.takeoverEchoTick != nowTick) {
            runtime.takeoverEchoTick = -1L; // stale — the expected despawn never surfaced
            runtime.takeoverEchoWireId = -1L;
        }
        return false; // same-tick id-mismatch: a real loss; the echo stays armed for its id
    }

    /**
     * The ONE reconcile rule unifying the T1 fly-in and the T4 remount: a seat is
     * due for a fly-in this frame boundary iff it is occupied, <b>combat-ready</b> (
     * {@link #recordCombatReady(int)} has latched: the serve loop saw the seat's first pilot
     * intent, so a hull never fields for a HELLO-admitted window that is still loading), has no
     * hull, is not dead-latched, and either never mounted this round (T1) or the respawn policy
     * allows a remount now (T4). Returns seat ids in ascending order; the serve loop schedules
     * the fly-ins and calls {@link #recordMountEstablished(int)} as each hull lands.
     * A seat whose fresh-claim pick apply is still pending
     * ({@link #claim(String, boolean)} armed it) is NOT due — the tenure gate. The serve adds one gate at its
     * fly-in drain: a due seat whose holder session its frame's session diff has not observed waits a frame.
     */
    public synchronized List<Integer> seatsDueForFlyIn(long nowTick) {
        requireArmed();
        List<Integer> due = new ArrayList<Integer>();
        for (SeatRuntime runtime : runtimeBySeatId.values()) {
            boolean seatOccupied = occupied.contains(runtime.seat.seatToken());
            if (seatOccupied && runtime.combatReady && !runtime.mounted && !runtime.deadLatched
                    && !runtime.pickApplyPending
                    && (runtime.neverMountedThisRound
                            || respawnPolicy.allowsRemountAt(nowTick, runtime.lossTick))) {
                due.add(runtime.seat.seatId());
            }
        }
        return due;
    }

    /**
     * Latch {@code seatId} combat-ready — the deploy gate. The serve loop calls
     * this for every pilot-intent frame it takes from the seat's session; the FIRST call latches
     * (returns {@code true} exactly once, so the caller logs the transition once) and
     * {@link #seatsDueForFlyIn(long)} refuses the seat a fly-in until it has run. The signal is
     * existing wire traffic — ANY {@code PilotIntentFrame}, neutral included: the client's combat
     * plugin starts its intent pump at mission load (its first intent is the documented
     * "combat began" signal), so first-intent-seen means the window is actually in combat, not
     * merely HELLO-admitted at game boot (the boot-shred + minutes-old-battlefield
     * shape). The latch is per-seat-per-MATCH and NEVER clears: not by vacate/re-claim (re-admit ≠
     * re-deploy — a D4.2 rejoin resumes without waiting for a fresh keypress) and not by
     * {@link #resetRoundSeats(Collection)} (round wraps redeploy ready seats directly). So a
     * re-claimed unmounted seat is due on the very next frame, without awaiting its new
     * session's first intent; what makes a re-claim's first fly-in still read its OWN tenure's
     * pick is the tenure gate ({@link #markPickApplyPending} / {@link #clearPickApplyPending})
     * and its team side the serve's pick poll, which runs after the
     * session diff that first sees the claim and before that frame's fly-ins — not this latch.
     */
    public synchronized boolean recordCombatReady(int seatId) {
        SeatRuntime runtime = requireArmedRuntime(seatId);
        if (runtime.combatReady) {
            return false;
        }
        runtime.combatReady = true;
        return true;
    }

    /** Whether {@code seatId} has latched combat-ready (armed seat machine only). */
    public synchronized boolean isCombatReady(int seatId) {
        return requireArmedRuntime(seatId).combatReady;
    }

    /**
     * Mark {@code seatId} as awaiting its
     * post-spool variant-pick apply — the tenure gate. While marked, {@link #seatsDueForFlyIn}
     * refuses the seat and {@link #deployableSeatIds} omits it, so the tenure's first fly-in
     * reads the tenure's own pick. An unminted seat is a no-op (never throws).
     * PRODUCTION arms the gate inside {@link #claim(String, boolean)}
     * (one monitor step with the occupancy add); this seam remains for the contract rows.
     * {@link #resetRoundSeats} deliberately does NOT touch the flag: the door's {@code finally}
     * around the provisioning always clears it (fail-open), and {@link #vacate(String,
     * SeatStateCause)} clears it with the seat's release.
     */
    public synchronized void markPickApplyPending(int seatId) {
        SeatRuntime runtime = runtimeBySeatId.get(seatId);
        if (runtime != null) {
            runtime.pickApplyPending = true;
        }
    }

    /**
     * Whether the tenure gate is armed for {@code seatId} —
     * the door reads it BEFORE printing {@code TENURE_APPLIED} and then clears, so the row
     * precedes the seat's {@code fly_in} row in every tee. False for an unminted seat.
     */
    public synchronized boolean pickApplyPending(int seatId) {
        SeatRuntime runtime = runtimeBySeatId.get(seatId);
        return runtime != null && runtime.pickApplyPending;
    }

    /**
     * Clear the tenure gate
     * ({@link #markPickApplyPending}); returns the PRIOR value (true = this call opened the
     * gate). An unminted seat returns false.
     */
    public synchronized boolean clearPickApplyPending(int seatId) {
        SeatRuntime runtime = runtimeBySeatId.get(seatId);
        if (runtime == null) {
            return false;
        }
        boolean prior = runtime.pickApplyPending;
        runtime.pickApplyPending = false;
        return prior;
    }

    /**
     * Whether {@code seatId} currently holds a
     * mounted hull — the door's held-hull test (a re-claim of a seat whose hull is still held,
     * grace ON or the grace-OFF AFK coast, is the SAME tenure). False for an unminted seat or an
     * unarmed seat machine — never throws (door path).
     */
    public synchronized boolean seatMounted(int seatId) {
        if (respawnPolicy == null) {
            return false;
        }
        SeatRuntime runtime = runtimeBySeatId.get(seatId);
        return runtime != null && runtime.mounted;
    }

    /**
     * The occupied AND combat-ready seat ids, ascending — the round-boundary redeploy's source
     * under the gate: a wrap redeploys exactly the seats whose pilots have proven
     * live this match; a claimed-but-never-ready seat stays ABSENT (the
     * unoccupied=absent law extended to not-yet-ready) and takes its T1 fly-in when its first
     * intent arrives. Armed seat machine only; admission/liveness accounting keeps
     * {@link #occupiedSeatIds()}. A seat whose fresh-claim
     * pick apply is still pending (the tenure gate) is NOT deployable — the wrap would otherwise
     * redeploy it in the PREVIOUS tenure's pick for the whole spool; it stays neverMounted and
     * takes the normal fly-in once the gate opens (the one fly-in shape).
     */
    public synchronized SortedSet<Integer> deployableSeatIds() {
        requireArmed();
        SortedSet<Integer> seatIds = new TreeSet<Integer>();
        for (SeatRuntime runtime : runtimeBySeatId.values()) {
            if (runtime.combatReady && !runtime.pickApplyPending
                    && occupied.contains(runtime.seat.seatToken())) {
                seatIds.add(runtime.seat.seatId());
            }
        }
        return seatIds;
    }

    /**
     * Whether any minted seat will (still) take a respawn fly-in — the
     * snapshot source's roster-0 HOLD supplier. {@code true} iff any seat is occupied and not
     * dead-latched. Deliberately WITHOUT a {@code !mounted} term: the source consults this only
     * on a zero-live-ship publish, where an occupied seat still reading "mounted" means its hull
     * vanished THIS tick and the serve loop's T3 {@code recordMountLoss} lands after the publish
     * (the exact same-tick mutual kill — the owner's explosion case); such a seat is one
     * bookkeeping tick from awaiting its remount, so holding is correct. Outside a roster-0
     * consult the value is meaningless (any healthy occupied seat reports {@code true}) — this is
     * a hold supplier, not a general seat-state query. {@code false} = terminal-empty: every seat
     * vacated (BYE/SILENCE/DISPLACED dropped occupancy) or dead-latched ({@code policy=disabled})
     * — the empty battlefield latches matchOver exactly as pre-B4(3b). Deliberately WITHOUT a
     * combat-ready term either: an occupied seat that has not latched yet is the
     * boot window — its pilot is admitted and one intent away from a T1 fly-in, so holding is
     * correct.
     */
    public synchronized boolean anySeatAwaitingRemount() {
        requireArmed();
        for (SeatRuntime runtime : runtimeBySeatId.values()) {
            if (occupied.contains(runtime.seat.seatToken()) && !runtime.deadLatched) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reset the mount axis at a round boundary (D12): dead-latches and loss ticks clear
     * everywhere; the seats in {@code mountedSeatIds} — the occupied seats the round-start deploy
     * actually gave hulls — come up mounted; every other seat is ABSENT-side (AFK collapses to
     * ABSENT: a vacant hull dies with the round and is not redeployed). Occupancy is untouched.
     * Emits nothing — the post-reset BASELINE burst ({@link #snapshotSeatBaseline(long)})
     * replicates the fresh table. The wrap's source is {@link #deployableSeatIds()},
     * read by the sim thread BEFORE this call — so a named seat can go vacant (BYE, kick,
     * silence on the reader thread) or lose its combat-ready latch in between. Such a seat prints
     * ONE {@code seat-authority round_reset_skipped seat=N reason=vacant|not_ready} line (token
     * renamed from {@code round_reset_vacated}: a not-ready seat is occupied)
     * and is
     * counted into the return value — never a throw (a throw here killed the wrap for a race the
     * caller cannot close). A VACANT seat stays MOUNTED: the reset already
     * fielded its hull, so it coasts like any BYE'd seat and a claim takes it over — marking it
     * ABSENT left a live hull under an unmounted seat, and the next claim's fly-in threw on the
     * live map (a serve death). A not-ready seat keeps the ABSENT-side collapse (D12 rows).
     * Naming an UNMINTED seat is a programming error and still throws.
     *
     *
     *
     * @return the number of named seats skipped as vacant or not combat-ready (callers may ignore)
     */
    public synchronized int resetRoundSeats(Collection<Integer> mountedSeatIds) {
        requireArmed();
        Objects.requireNonNull(mountedSeatIds, "mountedSeatIds");
        Set<Integer> mounted = new HashSet<Integer>(mountedSeatIds);
        for (Integer seatId : mounted) {
            if (runtimeBySeatId.get(seatId) == null) {
                throw new IllegalArgumentException("round reset names an unminted seat: " + seatId);
            }
        }
        int skipped = 0;
        StringBuilder skippedLines = null;
        for (Iterator<Integer> it = mounted.iterator(); it.hasNext(); ) {
            Integer seatId = it.next();
            SeatRuntime runtime = runtimeBySeatId.get(seatId);
            String reason = !occupied.contains(runtime.seat.seatToken()) ? "vacant"
                    : !runtime.combatReady ? "not_ready" : null;
            if (reason == null) {
                continue;
            }
            if ("not_ready".equals(reason)) {
                it.remove(); // vacant seats stay in the mounted set (javadoc)
            }
            skipped++;
            if (skippedLines == null) {
                skippedLines = new StringBuilder();
            } else {
                skippedLines.append('\n');
            }
            skippedLines.append("seat-authority round_reset_skipped seat=").append(seatId)
                    .append(" reason=").append(reason);
        }
        if (skippedLines != null) {
            // Round-wrap only and only on the race — not a per-frame line.
            System.out.println(skippedLines);
        }
        for (SeatRuntime runtime : runtimeBySeatId.values()) {
            boolean deployed = mounted.contains(runtime.seat.seatId());
            runtime.mounted = deployed;
            runtime.neverMountedThisRound = !deployed;
            runtime.deadLatched = false;
            runtime.lossTick = -1L;
            runtime.dismountEchoTick = -1L;
            runtime.takeoverEchoTick = -1L;
            runtime.takeoverEchoWireId = -1L;
            // W5: AFK collapses to ABSENT at the wrap, so grace state clears with it.
            runtime.graceState = SeatGraceState.NONE;
            runtime.graceDeadlineTick = -1L;
            runtime.graceEntryPending = false;
        }
        // Queued grace transitions from the dying round are stale (emits-nothing law above holds
        // for grace too — the engine lane resets its own bookkeeping at the wrap).
        pendingGrace.clear();
        return skipped;
    }

    /**
     * The per-admit (and post-reset) BASELINE burst: one full-state event per minted seat in
     * ascending seat order, stamped {@code simTick}, fresh seatSeqs from the same single-writer
     * counter as the drain. Sim thread only.
     */
    public synchronized List<SeatStateEvent> snapshotSeatBaseline(long simTick) {
        requireArmed();
        List<SeatStateEvent> events = new ArrayList<SeatStateEvent>(runtimeBySeatId.size());
        for (SeatRuntime runtime : runtimeBySeatId.values()) {
            events.add(eventFor(simTick, runtime, SeatStateCause.BASELINE));
        }
        return events;
    }

    /**
     * Drain the pending transition records — the once-per-tick single-writer point (sim thread):
     * each record resolves to the seat's CURRENT full state, stamped {@code simTick}, seatSeq
     * minted in emission order. Full-state resolution makes records idempotent and replay-safe;
     * a claim whose fly-in settled earlier in the same tick emits with the post-fly-in state.
     *
     * <p><b>Latest-wins per seat:</b> every record for one
     * seat in one drain resolves to the IDENTICAL full state (the runtime is read at emission),
     * the wire's only per-seat order key is {@code seatSeq}, and the client keeps the newest
     * seatSeq per seat and reads no cause ({@code PredictReconcileDatagramSession.recordSeatState})
     * — so of N same-tick records only the last one ever landed. Emitting all N was the
     * seat-churn SEAT-fan amplifier (N admit→kill cycles in one tick fanned ~2N frames to
     * EVERY peer; the door budget bounds the rate, this bounds the fan at its source):
     * the drain now emits ≤ 1 frame per seat per tick carrying the LAST record's cause, in the
     * last record's position (so cross-seat emission order is unchanged). Folded records count
     * in {@link #seatRecordsCoalesced()} (serve row {@code seat_coalesced=}).
     */
    public synchronized List<SeatStateEvent> drainSeatEvents(long simTick) {
        requireArmed();
        LinkedHashMap<Integer, SeatStateCause> latestBySeat = new LinkedHashMap<Integer, SeatStateCause>();
        for (PendingTransition transition : pending) {
            if (latestBySeat.remove(transition.seatId()) != null) {
                seatRecordsCoalesced++;
            }
            latestBySeat.put(transition.seatId(), transition.cause());
        }
        pending.clear();
        List<SeatStateEvent> events = new ArrayList<SeatStateEvent>(latestBySeat.size());
        for (Map.Entry<Integer, SeatStateCause> latest : latestBySeat.entrySet()) {
            events.add(eventFor(simTick, runtimeBySeatId.get(latest.getKey()), latest.getValue()));
        }
        return events;
    }

    /** Cumulative pending records folded away by {@link #drainSeatEvents(long)} (latest-wins). */
    public synchronized long seatRecordsCoalesced() {
        return seatRecordsCoalesced;
    }

    /**
     * Whether a transition record for {@code seatId} is queued, un-drained — ANY writer: the three
     * mount sites' {@link #recordMountEstablished}, {@link #recordMountLoss}, and the door's
     * {@link #claim(String, boolean)} / {@link #vacate(String, SeatStateCause)}. An unknown or
     * unminted seat id reads false (no {@code requireArmedRuntime} — a pure read, never a throw).
     *
     * <p>The serve's pre-step intent take
     * reads it BEFORE (g) {@link #drainSeatEvents} turns the queue into this tick's SEAT frames, and
     * refuses to stage a seat whose SEAT frame has not gone out yet (its MOUNT tick above all).
     */
    public synchronized boolean hasPendingTransition(int seatId) {
        for (PendingTransition transition : pending) {
            if (transition.seatId() == seatId) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code seatId} currently has a mounted hull (armed seat machine only). */
    public synchronized boolean isMounted(int seatId) {
        return requireArmedRuntime(seatId).mounted;
    }

    /** Whether {@code seatId} is latched dead-for-the-round (armed seat machine only). */
    public synchronized boolean isDeadLatched(int seatId) {
        return requireArmedRuntime(seatId).deadLatched;
    }

    // ------------------------------------------------------------------------------------------
    // The W5 reconnect-grace machine —
    // server-internal sub-states of AFK, ZERO wire. Game-free leg 1: state + transitions here;
    // the engine verbs (cautious-AI attach/detach, personality save/restore) live in leg 2's
    // runtime/SeatGraceLane, which drains pumpGraceTransitions on the sim thread.
    // ------------------------------------------------------------------------------------------

    /**
     * Arm the grace machine with the parsed {@code cc.match.graceSeconds}/{@code cc.match.parkPolicy}
     * pair. Requires the seat machine armed first (grace is a sub-state of its AFK state); arming
     * twice is a wiring bug and throws. An {@link MatchGracePolicy#off() OFF} policy arms legally
     * and the machine stays inert — the compiled default (0 = OFF) keeps every certified lane
     * byte-identical (toggle discipline).
     */
    public synchronized void armGraceMachine(MatchGracePolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("grace policy must not be null");
        }
        requireArmed();
        if (gracePolicy != null) {
            throw new IllegalStateException("grace machine already armed");
        }
        gracePolicy = policy;
    }

    /** Whether {@link #armGraceMachine(MatchGracePolicy)} has run. */
    public synchronized boolean graceMachineArmed() {
        return gracePolicy != null;
    }

    /**
     * The once-per-tick grace pump (sim thread — the single writer that mints deadlines from sim
     * ticks, the sim-tick-clock DECISION): resolves pending TRUE-vacate entry marks into
     * GRACE (deadline = {@code nowTick + graceTicks}; a mark whose seat re-claimed or lost its
     * hull first simply drops), parks GRACE seats whose deadline has arrived
     * ({@code PARK_STAY_AI | PARK_DRIFT} per policy), and drains the queued transitions in
     * chronological order for the engine lane to act on. Idle ticks return the empty list.
     */
    public synchronized List<SeatGraceTransition> pumpGraceTransitions(long nowTick) {
        requireGraceArmed();
        if (nowTick < 0L) {
            throw new IllegalArgumentException("grace pump nowTick must be >= 0, got: " + nowTick);
        }
        for (SeatRuntime runtime : runtimeBySeatId.values()) {
            if (runtime.graceEntryPending) {
                runtime.graceEntryPending = false;
                if (!occupied.contains(runtime.seat.seatToken()) && runtime.mounted
                        && runtime.graceState == SeatGraceState.NONE) {
                    runtime.graceState = SeatGraceState.GRACE;
                    runtime.graceDeadlineTick = nowTick + gracePolicy.graceTicks();
                    pendingGrace.add(new SeatGraceTransition(
                            runtime.seat.seatId(), SeatGraceTransitionKind.ENTER_GRACE));
                }
            }
            if (runtime.graceState == SeatGraceState.GRACE && nowTick >= runtime.graceDeadlineTick) {
                runtime.graceState = SeatGraceState.PARKED;
                runtime.graceDeadlineTick = -1L;
                pendingGrace.add(new SeatGraceTransition(runtime.seat.seatId(),
                        gracePolicy.parkPolicy() == MatchGracePolicy.ParkPolicy.DRIFT
                                ? SeatGraceTransitionKind.PARK_DRIFT
                                : SeatGraceTransitionKind.PARK_STAY_AI));
            }
        }
        if (pendingGrace.isEmpty()) {
            return List.of();
        }
        List<SeatGraceTransition> drained = List.copyOf(pendingGrace);
        pendingGrace.clear();
        return drained;
    }

    /** The seat's grace sub-state (armed grace machine only — tests + the engine lane). */
    public synchronized SeatGraceState seatGraceState(int seatId) {
        requireGraceArmed();
        return requireArmedRuntime(seatId).graceState;
    }

    /**
     * The W3 command-transfer hook: whether {@code seatId}'s hull is another
     * player's legal transfer take. Two ways in: PARKED — its grace window expired
     * unreclaimed; or, with the grace machine armed OFF (F-A),
     * a vacated seat still mounted on its hull — grace OFF is a ZERO-LENGTH window, so the
     * drifting pilotless coast is IMMEDIATELY abandoned (by design: the vacated
     * hull IS the transfer fodder; not a vanilla-parity mandate). Deliberately a bare
     * boolean ({@code SeatRebindArbiter} stays generic, never consuming the state enum) and
     * deliberately lenient when the grace machine is UNARMED (policy null — contract
     * fakes/legacy serves): "no grace machine" truthfully means no seat is ever
     * abandoned-eligible (the armed-seat-machine + minted-seat validation still runs).
     * Grace-ON vacates stay PARKED-gated — the {@code graceEntryPending} transient and the
     * GRACE window must never list.
     */
    public synchronized boolean abandonedEligible(int seatId) {
        SeatRuntime runtime = requireArmedRuntime(seatId);
        if (runtime.graceState == SeatGraceState.PARKED) {
            return true;
        }
        return gracePolicy != null && gracePolicy.off() && runtime.mounted
                && !occupied.contains(runtime.seat.seatToken());
    }

    private void requireGraceArmed() {
        requireArmed();
        if (gracePolicy == null) {
            throw new IllegalStateException(
                    "grace machine is not armed (the serve lane arms it from cc.match.graceSeconds"
                            + " / cc.match.parkPolicy after arming the seat machine)");
        }
    }

    /**
     * The seat ids currently occupied by a live connection, ascending — the occupancy-gated
     * round-start deploy's source (D6: descriptor-path deploy = occupied seats + all AI rows).
     */
    public synchronized SortedSet<Integer> occupiedSeatIds() {
        SortedSet<Integer> seatIds = new TreeSet<Integer>();
        for (String token : occupied) {
            seatIds.add(seatsByToken.get(token).seatId());
        }
        return seatIds;
    }

    private SeatStateEvent eventFor(long simTick, SeatRuntime runtime, SeatStateCause cause) {
        boolean seatOccupied = occupied.contains(runtime.seat.seatToken());
        long shipId = runtime.mounted ? runtime.seat.shipId() : 0L;
        return new SeatStateEvent(
                simTick, runtime.seat.seatId(), seatOccupied, shipId, cause, nextSeatSeq++);
    }

    private void requireArmed() {
        if (respawnPolicy == null) {
            throw new IllegalStateException(
                    "seat machine is not armed (a serve lane arms it when cc.match.respawnPolicy"
                            + " enables respawn — N-peer always, legacy iff != disabled)");
        }
    }

    private SeatRuntime requireArmedRuntime(int seatId) {
        requireArmed();
        SeatRuntime runtime = runtimeBySeatId.get(seatId);
        if (runtime == null) {
            throw new IllegalArgumentException("seat is not minted by this match: " + seatId);
        }
        return runtime;
    }

    private static String requireToken(String token) {
        if (token == null) {
            throw new IllegalArgumentException("seat token must not be null");
        }
        if (token.isBlank()) {
            throw new IllegalArgumentException("seat token must not be blank");
        }
        return token;
    }
}
