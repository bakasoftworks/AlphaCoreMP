package cc.starsector.client;

import cc.starsector.client.net.ClientCommandTransfer;
import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;

import com.fs.starfarer.api.combat.ShipAPI;

import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * The XFER command-transfer client lane (NEW-LANE-LAW
 * collaborator of {@link PredictReconcileCombatPlugin}): owns the whole own-view transfer
 * story that L3 took count-only.
 *
 * <p><b>Pumps (plugin-owned drive calls):</b> {@link #intake} at the registryShape fold —
 * AFTER {@code mmnt.drain()}, BEFORE {@code rapp.drain()} (a BEGIN registers {@link #podWireId}
 * before this frame's RAPP fold, per the ordering law); {@link #applyStaged} after
 * {@code spawnRemoteEntities} (replica bodies exist to adopt/re-point); {@link #pinPump} as the
 * FINAL statement of {@code advance()} — the engine advances plugins LAST (transfer
 * manager @1346, pod AI @1358, pod physics @1386, plugins @1547), so the 4-field hard-set is
 * the last pose word the renderer sees, on top of the pod's KEPT vanilla AI.
 *
 * <p><b>Trigger lane:</b> poll the vanilla transfer-manager state via
 * {@link TransferManagerAdapter}; on the IDLE-departure flip, once per episode, resolve the
 * vanilla TO ship to its wire id and send {@code /transfer #<id>} up the CTRL chat lane. The
 * server stays the only authority — every local effect waits for the XFER DOWN family.
 * Fail-closed additions recorded at the S2 land (not in the sheet, flagged for review): an
 * unresolvable TO and a send failure retarget the pod home immediately, and a sent trigger
 * that draws NEITHER BEGIN nor DENY within {@link #BEGIN_ACK_MILLIS} retargets home too —
 * otherwise a server with {@code transfer.enabled=false} (the STRICT default) would let the
 * LOCAL vanilla machine run to a landing swap the server never granted (a concealed desync).
 *
 * <p><b>Own BEGIN (one-entity):</b> TRANSIT — the LOCAL vanilla
 * pod is adopted (KEEP {@code TransferCommandAI} — never {@code setAI(null)};
 * CR deduct flag off — server owns CR), becomes {@code plugin.ownShip} and is pose-pinned from
 * the own seat row S by {@link #pinPump} (the soft own-reconcile gates consult
 * {@link #ownTransit()}); the vacated hull re-homes under F as a neutralized remote
 * ({@link #switchOwnOntoPod} — no decision hold, no source pin, no engine-visible lock: the
 * {@code setControlsLocked(true)} greyed the warroom's Transfer button).
 * {@link #suppressMintFor} keeps only its H1-retired-id arm. No local pod within the search
 * budget ⇒ observer mode (late-join; the cc_pod own body mints from S).
 *
 * <p><b>DENY/ABORT:</b> typed retarget-back FIRST (re-pins the vanilla
 * landing key — vanilla has NO return-home branch, the re-invoke is REQUIRED), then the
 * bounded ≈5 s {@code setPlayerShipExternal(source)} backstop clocked from the RETARGET;
 * transit ends at the local swap-back with the 7 pose baselines re-armed.
 *
 * <p><b>COMMIT:</b> own = detect LEFT-THE-POD then unconditional
 * idempotent force; capture-then-rewrite — defuse-first the RETIRING target row, adopt the
 * target body ({@link OwnMountLifecycleLane#adoptOwnTransferHull}), mint the source body a
 * FRESH remote row keyed fromWireId (2-arg ctor; NEVER {@code engine.removeEntity}).
 * Remote = the same choreography on rows A/B without the own-pointer flip. Retired ids
 * enter {@link #retiredWireIds} (until round wrap) so a stale pre-COMMIT snapshot can never
 * re-mint a ghost twin (H1); a redelivered COMMIT drops as an anomaly (H2).
 *
 * <p>Pose pin SOURCE (the S3 jitter lever landed): a self-contained
 * 2-sample delayed-lerp slot per pinned body ({@link DelayedLerpSlot} — pod + transit
 * source; own-file carve), fed
 * once per NEW sim tick inside {@link #pinPump}, read every render frame one measured
 * arrival-interval behind the newest sample. Every SNAP edge reduces to "clear the slot ⇒
 * next read hard-sets raw" (worst case == the S2 raw pin). {@code FoeInterpLane} reuse was
 * structurally rejected ({@code targetFor} fires its meters per call).
 *
 * <p><b>S3 edges:</b> mid-flight retarget lane (warroom retargets mirror
 * to the server via {@code /transfer #new}; a BEGIN while in transit with a changed to/pod is
 * a RETARGET BEGIN; the retarget ack-timeout re-pins the local machine to the GRANTED target
 * — never home — via {@link TransferManagerAdapter#retargetTo}, R6: retarget denials are
 * chat-only so the timeout IS the signal); the concealed-swap guard (a fast local
 * landing beats the ack window — DENY/timeout/late-ABORT at IDLE force the player off the
 * ungranted body); the stuck-B wrap latch (non-IDLE at round start with no IDLE
 * observed = vanilla machine bricked by a mid-flight wrap — suppress trigger sends, loud log
 * once; no client-side repair exists); every anomaly transit exit routes through
 * {@link #endTransitRestore} (@0334 — a malformed COMMIT must never strand the player on the
 * intangible pod with the source controls-locked).
 *
 * <p><b>W3-FIX (the flip-watch defects):</b> W3-A — the home-collapse is
 * 3-way everywhere ({@link TransferManagerAdapter#retargetToSource} is IN_FLIGHT-gated):
 * IN_FLIGHT retargets home now; LANDING arms the {@link #pendingCollapse} latch (a typed
 * retarget at LANDING trips B's unconditional {@code state=IN_FLIGHT} with
 * {@code pod.isLanding} stuck true — B's sole swap site goes unreachable forever = the
 * E1 strand), pumped in {@link #applyStaged} at !ownTransit until the machine exits LANDING;
 * IDLE runs the concealed-swap guard. The stuck-B watchdog
 * ({@link #maintainStuckWatchdog}) is the last-ditch player restore for any non-IDLE machine
 * holding the player off the own hull with every designed lane quiet. W3-B — a typed
 * {@code /transfer} own BEGIN with no local pod DRIVES the vanilla spawn branch
 * ({@link #maintainLocalPodSynth}) so typed == clicked; observer mode is last-resort only.
 *
 * <p><b>ONE-ENTITY pod — an IDENTITY change over everything above:</b>
 * the server commits the seat ONTO the pod at BEGIN (the seat wire id S IS the pod for the whole
 * flight; the vacated source rides a FRESH id F = {@code fromWireId} of the BEGIN fan, announced
 * by RAPP the same fold; a RESUME from a stranded pod body carries {@code from == S}). Vanilla
 * {@code B} STAYS the local presentation machine (it cannot be bypassed for a clicked transfer
 * and has no exit but a landing); what changes: the LOCAL vanilla pod becomes
 * {@code plugin.ownShip} at adopt, the source re-homes under F at BEGIN (not COMMIT), the pin
 * feeds from the S row, and no source pin / decision hold / twin defuse exists any more. ABORT
 * from-law: {@code from == F} = RETURNING (transit KEPT, B re-pinned home, the dock-back
 * COMMIT with {@code to == F} ends it), {@code from == S} = STRANDED (the invisible-landing exit:
 * hide the local pod, mint the cc_pod own body at the S pose at rest, let B land the hidden pod
 * onto it). Remote seats: BEGIN re-homes their source under F and re-mints the seat replica as
 * the flying pod ({@code CollisionClass.NONE} while {@link #isFlyingPodSeat}); the STRANDED
 * abort flips it SHIP.
 */
final class CommandTransferLane {

    /** Bounded wait for the LOCAL vanilla landing swap (anim 3.0 s) before forcing. */
    static final long SWAP_FORCE_MILLIS = 5000L;
    /** Trigger sent, neither BEGIN nor DENY back ⇒ fail-closed retarget home (see class doc). */
    static final long BEGIN_ACK_MILLIS = 4000L;
    /** Frames to hunt the local vanilla pod after an own BEGIN before observer mode (~2 s). */
    static final int POD_SEARCH_FRAME_BUDGET = 120;
    /** W3-FIX stuck-B watchdog dwell — >> the 3.0 s landing anim + the 4 s ack window. */
    static final long STUCK_DWELL_MILLIS = 8000L;

    private final PredictReconcileCombatPlugin plugin;
    private final TransferManagerAdapter adapter = new TransferManagerAdapter();

    // ---- trigger episode ----
    private boolean triggerSentThisEpisode;
    private boolean departureTwinWrittenThisEpisode; // resets with triggerSentThisEpisode
    private boolean awaitingBegin;
    private long triggerSentAtMillis;

    // ---- S3 retarget episode ----
    /** The target wire id we last ASKED the server for this episode (0 = none). */
    private long lastSentTargetWireId;
    /** The target wire id the server last GRANTED via BEGIN (0 = no live flight). */
    private long grantedTargetWireId;
    private boolean retargetAckPending;
    private long retargetSentAtMillis;

    // ---- S3 stuck-B wrap latch ----
    /** Round-scoped; UNKNOWN is NOT an IDLE observation. Cleared by {@link #resetRound}. */
    private boolean idleObservedThisRound;
    /** Run-scoped one-shot for the bricked-machine loud log (never reset). */
    private boolean brickLogged;

    // ---- W3-FIX deferred collapse + stuck-B watchdog + typed-trigger synth ----
    /** Home-collapse wanted but the machine was not IN_FLIGHT — pumped at !ownTransit. */
    private boolean pendingCollapse;
    /** Wall-clock start of the stuck-B dwell; 0 = the watchdog predicate is not held. */
    private long stuckSinceMillis;
    /** Run-scoped one-shot for the watchdog loud log (the brickLogged mirror). */
    private boolean stuckRestoreLogged;
    /** W3-B: the synth drive fired this transit (at most one per episode). */
    private boolean synthDriven;

    // ---- post-abort home-flight watch + idle divergence floor ----
    /**
     * RC2: armed at the FORCED abort restore — vanilla {@code B} is then still IN_FLIGHT with
     * a live {@code TransferCommandAI} pod that cannot be idled from outside (no cancel; and
     * removing the pod bricks B for the mission — B's landing code never runs ⇒ every later trigger
     * dies in the non-IDLE else-branch). So the pod FLIES: the pump keeps its TO on the
     * in-play LIVE own body (a respawn under the same wire id included) so the vanilla landing
     * swap lands on the RIGHT hull, and the concealed-swap guard runs at the first IDLE.
     * Survives {@link #clearTransit}; {@link #resetRound} is its unconditional clear.
     */
    private boolean homeFlightWatch;
    /** One loud line per armed watch (the re-point retries silently until TO == own). */
    private boolean homeFlightRepointLogged;
    /** FLOOR: consecutive quiet-lane IDLE frames with the engine's player pointer
     *  off {@code plugin.ownShip} — the unified root of the flight's "seated in the
     *  citadel" (a LOCAL landing the server never committed). */
    private int idleDivergenceFrames;
    /** Loud on the first repair per divergence; later retries of a persisting one stay quiet. */
    private boolean idleDivergenceLogged;
    /** Dwell before the floor fires — every designed pointer flip re-points ownShip in the same
     *  frame (grep {@code ownShip =}), so 2 frames only insures against an unknown seam. */
    static final int IDLE_DIVERGENCE_DWELL_FRAMES = 2;

    // ---- S3 interp-fed pin (own-file carve) ----
    private final DelayedLerpSlot podSlot = new DelayedLerpSlot();
    private long lastFedSimTick = Long.MIN_VALUE;

    // ---- transit (ONE-ENTITY) ----
    private boolean ownTransit;
    private boolean observerMode;
    /** == the own seat wire id S while in transit (S IS the pod); 0 = none. Kept as a field for
     *  the log lines and the pin feed key. */
    private long podWireId;
    /** Vanilla B's LOCAL pod — {@code plugin.ownShip} from adopt until COMMIT/strand. */
    private ShipAPI localPod;
    /** The body VACATED at BEGIN (the former own hull, or the stranded cc_pod body on a RESUME):
     *  re-homed under F at adopt, then kept ONLY as B's landing key for RETURNING (never pinned,
     *  never decision-held — it is a neutralized remote). */
    private ShipAPI sourceHull;
    /** BEGIN fan {@code fromWireId}: F (fresh source id) or S (RESUME — nothing to re-home). */
    private long beginFromWireId;
    /** The seat row's variant captured at the BEGIN fold (BEFORE the same frame's RAPP repoint
     *  to cc_pod) — the legacy back-fill for the re-homed source (announced row wins). */
    private String sourceVariant;
    /** The source re-home (or stranded-body removal) ran for this transit — once. */
    private boolean sourceReHomed;
    /** The own BEGIN folded while the seat claim was still pending — the
     *  "source" is the claim-at-play placeholder (or nothing yet), never F's vacated hull. */
    private boolean sourceIsClaimPlaceholder;
    /** Observer mode owes the S-row cc_pod own mint at the next snapshot (pinPump). */
    private boolean observerMintPending;
    /** The observer-mode cc_pod own body (no B owns it — dropped at COMMIT / strand). */
    private ShipAPI observerBody;
    /** STRANDED abort taken; the invisible-landing exit runs at the next snapshot. */
    private boolean strandPending;
    private int strandReason;
    /** The hidden local pod flying its invisible landing onto the stranded own body — alpha
     *  re-asserted per frame until B reaches IDLE (post-transit, survives clearTransit). */
    private ShipAPI strandHiddenPod;
    /** RETURNING abort: transit KEPT while the server flies the pod home; ends at the
     *  dock-back COMMIT. Silences the retarget mirror (the TO changes are ours). */
    private boolean returning;
    private boolean landingPollNoted; // The once-per-transit "TO == own, not source" line
    private int podSearchFrames;

    // ---- remote seats ----
    /** Seat ids whose pod is FLYING (remote BEGIN applied, no COMMIT / strand yet): the W3-P
     *  replica mint reads NONE for them. Round-scoped. */
    private final HashSet<Long> flyingPodSeats = new HashSet<Long>();
    /** Remote BEGIN staging: the seat row's PRE-RAPP variant captured at the fold, keyed seat. */
    private final java.util.HashMap<Long, String> stagedBeginVariant =
            new java.util.HashMap<Long, String>();

    // ---- own COMMIT wait / own ABORT return ----
    private ClientCommandTransfer pendingOwnCommit;
    private long commitWaitStartMillis;
    /** W3-J(i): bound for the rowless own-COMMIT park — long enough for a same-window
     *  spawn-on-sight/RAPP-fold bind (drains in frames; the observed mount resolve was 3 ms),
     *  short because a row that hasn't appeared by then never will (retired ids re-key fresh). */
    static final long ROWLESS_PARK_MILLIS = 1000L;
    /** A remote COMMIT onto a station-module target whose MMNT mount is still
     *  pending parks this long, then retires UNAPPLIED (MMNT owns the adopt; the source row
     *  re-homes by its RAPP announce + spawn-on-sight). Keyed by seat wire id. */
    static final long MODULE_PARK_MILLIS = 2000L;
    private final java.util.HashMap<Long, Long> moduleParkStart =
            new java.util.HashMap<Long, Long>();
    private long rowlessParkStartMillis;

    // ---- staged REMOTE edges: BEGIN (post-RAPP apply) + COMMIT (park-and-retry, the MMNT
    // fail-soft envelope) ----
    private final ArrayDeque<ClientCommandTransfer> staged =
            new ArrayDeque<ClientCommandTransfer>();

    /** Ids retired this round (post-COMMIT toWireId, ended own pods): never re-mint. */
    private final HashSet<Long> retiredWireIds = new HashSet<Long>();

    // ---- counters (debrief-grade, logged on the lane's own lines) ----
    private int edgesTaken;
    private int commitsApplied;
    private int anomaliesDropped;
    private long podPinFrames;

    CommandTransferLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** PRCP soft-own-reconcile gate read — the ownJumpPatience/ownMounted shape. */
    boolean ownTransit() {
        return ownTransit;
    }

    /**
     * Is {@code body} vanilla B's LIVE
     * local transfer pod (= {@code plugin.ownShip} mid-transit)?
     * {@link OwnMountLifecycleLane#adoptOwnMountedModule}'s raced-hull heal gates on this: its
     * guard (own alive && != adopted body) was designed for the respawn race, and a
     * pod→module-DEST transit satisfies it too — a {@code removeEntity} on B's pod bricks B for
     * the mission (no state setter; B lands and removes its own pod). The legit heal
     * target — a respawn-edge client mint, or the observer-mode cc_pod body — is never B's pod.
     */
    boolean ownsTransitPodBody(ShipAPI body) {
        return ownTransit && body != null && localPod == body;
    }

    /** A REMOTE seat whose pod is flying — the W3-P replica mint reads NONE. */
    boolean isFlyingPodSeat(long seatWireId) {
        return flyingPodSeats.contains(Long.valueOf(seatWireId));
    }

    /**
     * The RAPP fold's transfer gate: is a
     * foreign BEGIN for {@code seatWireId} STAGED this frame (intake ≺ rapp.drain ≺ applyStaged)
     * and NOT a retarget re-fan? The server fans F's true-variant row ≺ the seat's S→cc_pod
     * repoint ≺ the BEGIN on ONE CTRL FIFO ({@code CommandTransferServeLane.beginOntoSpawnedPod}:
     * source RAPP under F, then {@code announceSeatVariantIfRepointed}, then {@code fanTransferFrame}),
     * so the repoint row folds while the BEGIN sits staged. Without this gate the REPOINT arm
     * removed the ALIVE source and re-minted S as a cc_pod replica, and {@link #applyRemoteBegin}
     * then re-homed THAT body under F — on a late SNAP (F's pose not yet walked) the
     * variant-string check passed and a shuttle body drove the source hull's path.
     * While staged, the repoint re-points the registry only and keeps the source body
     * bound; the BEGIN's apply re-homes the ORIGINAL body under F and unbinds S for the cc_pod
     * re-mint. Split-frame arrival (repoint folded a frame before the BEGIN was decoded) is
     * self-healing without the gate: the staged variant then reads cc_pod ≠ F's announced hull,
     * so {@code reHomeRemoteSource} drops the stray capture and F mints its true shape.
     */
    boolean hasStagedBeginFor(long seatWireId) {
        if (flyingPodSeats.contains(Long.valueOf(seatWireId))) {
            return false; // retarget re-fan: the pod already IS the seat's body (no re-home)
        }
        for (ClientCommandTransfer e : staged) {
            if (e.isBegin() && e.seatWireId() == seatWireId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The kill lane's adopt-target consult: true while {@code wireId}
     * is a transfer's imminent adopt target (the decoded own COMMIT's toWireId; mid-transit the
     * granted flight target, against the decode race; or a staged remote COMMIT's toWireId — the
     * remote-seat twin of the same race). A COMMIT re-key retires the target id as a same-tick
     * DEPARTED — an id retirement, never a body death — so
     * {@link FoeStateLaneCoordinator#killRemote} holds its removal (bounded) and the adopt
     * consumes the LIVE body (E3 @170416: the ejected full-hull Aurora, wreck-adopted honestly).
     */
    boolean isPendingAdoptTarget(long wireId) {
        if (wireId <= 0) {
            return false;
        }
        ClientCommandTransfer pc = pendingOwnCommit;
        if (pc != null && pc.toWireId() == wireId) {
            return true;
        }
        if (ownTransit && grantedTargetWireId == wireId) {
            return true;
        }
        for (ClientCommandTransfer s : staged) {
            if (s.isCommit() && s.toWireId() == wireId) { // staged BEGINs name no adopt
                return true;
            }
        }
        return false;
    }

    /**
     * A guard copied from an idiom inherits the idiom's RESETS, not just its
     * shape: the kill lane's adopt-hold stamp ({@code RemoteEntity.adoptHoldSinceTick}) is
     * disarmed whenever this lane stops naming the id (episode end, staged-COMMIT consume,
     * abort, round wrap) and at pose-land (FSLC). A stamp outliving its transfer would age past
     * {@code TRANSFER_ADOPT_POSE_HOLD_TICKS}, so a LATER same-id transfer's first absence would
     * take the timeout branch instantly and eject the LIVE adopt target — the exact W3-H race
     * the hold exists to prevent. Render-thread only (the remoteEntities discipline).
     */
    private void clearAdoptHold(long wireId) {
        if (wireId <= 0) {
            return;
        }
        PredictReconcileCombatPlugin.RemoteEntity re =
                plugin.remoteEntities.get(Long.valueOf(wireId));
        if (re != null) {
            re.adoptHoldSinceTick = Long.MIN_VALUE;
        }
    }

    /**
     * Replica-mint suppression (RAPP fold + the spawn-on-sight walk): every H1-retired id. The
     * own pod arm is GONE: the pod IS the own seat id, never a remote row.
     */
    boolean suppressMintFor(long wireId) {
        return retiredWireIds.contains(Long.valueOf(wireId));
    }

    /** Edge fold + trigger poll — the registryShape intake site (BEFORE rapp.drain()). */
    void intake(java.util.List<ClientCommandTransfer> edges) {
        pollLocalTrigger();
        if (edges == null || edges.isEmpty()) {
            return;
        }
        edgesTaken += edges.size();
        plugin.commandTransferFramesTaken += edges.size(); // the counter stays honest
        for (ClientCommandTransfer e : edges) {
            try {
                fold(e);
            } catch (Throwable t) {
                anomaliesDropped++;
                PilotWatchLog.error("cc-transfer edge fold FAILED (consumed-with-error) kind="
                        + e.kind() + " seat=" + e.seatWireId(), t);
            }
        }
    }

    private void pollLocalTrigger() {
        // Anchor gate: sideAuthority() is only trustworthy once the own hull bound (a poisoned
        // side would feed getFleetManager garbage and dead-latch the adapter for nothing).
        if (!adapter.usable() || plugin.session == null || plugin.ownShip == null) {
            return;
        }
        int state = adapter.state(plugin.sideAuthority());
        if (state == TransferManagerAdapter.STATE_IDLE
                || state == TransferManagerAdapter.STATE_UNKNOWN) {
            stuckSinceMillis = 0L; // the watchdog dwell holds only across contiguous non-IDLE reads
            if (state == TransferManagerAdapter.STATE_IDLE) {
                idleObservedThisRound = true; // UNKNOWN is NOT an IDLE observation
            }
            triggerSentThisEpisode = false; // episode ends when the machine returns to IDLE
            departureTwinWrittenThisEpisode = false;
            lastSentTargetWireId = 0L;
            return;
        }
        // stuck-B wrap latch: non-IDLE before ANY IDLE observation this round = the
        // pre-wrap flight's bricked machine (swept pod; the sole →IDLE exit needs a landing
        // that can never finish, and B has no state setter). Suppress every send — the
        // suppression precedes the send, so awaitingBegin/ack never arm on a dead episode.
        if (!idleObservedThisRound) {
            if (!brickLogged) {
                brickLogged = true;
                PilotWatchLog.error("cc-transfer: vanilla transfer machine is non-IDLE at round"
                        + " start (mid-flight round wrap) — BRICKED for this mission; trigger"
                        + " sends suppressed (no client-side repair exists; reload clears it)",
                        null);
            }
            return;
        }
        maintainStuckWatchdog(); // State is non-IDLE from here — dwell-count it
        if (!triggerSentThisEpisode) {
            writeAdoptedTwinAtDeparture(); // vanilla's departure just wrote the build-time captain
        }
        if (triggerSentThisEpisode || ownTransit) {
            maintainRetargetPoll(); // The episode lives — mirror warroom retargets
            return;
        }
        triggerSentThisEpisode = true; // one shot per episode, success or fail
        ShipAPI to = adapter.transferTarget(plugin.sideAuthority());
        long wireId = to != null ? plugin.wireIdForLocalShip(to) : -1L;
        // Row 4 issuer instrument — the TARGET's and own order tuples at the
        // trigger + the live X/T/E key states (Transfer=45, Retreat=20, Direct retreat=18).
        try {
            String keys;
            try {
                keys = org.lwjgl.input.Keyboard.isKeyDown(45) + "/" + org.lwjgl.input.Keyboard.isKeyDown(20)
                        + "/" + org.lwjgl.input.Keyboard.isKeyDown(18);
            } catch (Throwable kt) {
                keys = "?";
            }
            PilotWatchLog.info("cc-transfer trigger orders: target wire=" + wireId + " {"
                    + ReplicaOrderSweep.describe(plugin.engine, to) + "} own {"
                    + ReplicaOrderSweep.describe(plugin.engine, plugin.ownShip) + "} keysX/T/E=" + keys);
        } catch (Throwable ignore) {
        }
        // The warroom trigger set the vanilla CR-deduct flag — clear it on
        // EVERY branch below (server owns CR; a fail-closed home-landing must not deduct).
        adapter.setCrDeductEnabled(plugin.sideAuthority(), false);
        if (wireId <= 0L) {
            // Fail-closed: the vanilla TO is not a wire body — no server ask is possible.
            boolean retargeted = adapter.retargetToSource(plugin.sideAuthority());
            if (!retargeted) {
                armPendingCollapse("trigger TO not wire-resolvable");
            }
            PilotWatchLog.info("cc-transfer trigger: local TO not wire-resolvable"
                    + " -> pod retargeted home (fail-closed, retargeted=" + retargeted + ")");
            return;
        }
        try {
            plugin.session.sendChat("/transfer #" + wireId);
            awaitingBegin = true;
            triggerSentAtMillis = System.currentTimeMillis();
            lastSentTargetWireId = wireId;
            PilotWatchLog.info("cc-transfer trigger: sent /transfer #" + wireId);
        } catch (Throwable t) {
            boolean retargeted = adapter.retargetToSource(plugin.sideAuthority());
            if (!retargeted) {
                armPendingCollapse("trigger send FAILED");
            }
            PilotWatchLog.info("cc-transfer trigger: send FAILED (" + t
                    + ") -> pod retargeted home (fail-closed, retargeted=" + retargeted + ")");
        }
    }

    /**
     * Vanilla's transfer departure
     * writes {@code setCaptain(getOriginalCaptain())} onto the own
     * hull at the X press, BEFORE any BEGIN — on the claim's in-place-adopted body that is the
     * build-time person, not the seat's twin the server keeps. Once per episode, at the first
     * non-IDLE read, the remembered twin goes back onto the Ship (and its member); a body that is
     * not the adopted one has no twin and is untouched. Package-private so a row drives it
     * without staging the vanilla machine. Returns true iff the twin was written.
     */
    boolean writeAdoptedTwinAtDeparture() {
        if (departureTwinWrittenThisEpisode) {
            return false;
        }
        departureTwinWrittenThisEpisode = true;
        ShipAPI own = plugin.ownShip;
        com.fs.starfarer.api.characters.PersonAPI twin = plugin.ownSeat.adoptedOriginalOrNull(own);
        if (twin == null) {
            return false;
        }
        try {
            own.setCaptain(twin);
            com.fs.starfarer.api.fleet.FleetMemberAPI member = own.getFleetMember();
            if (member != null) {
                member.setCaptain(twin);
            }
            return true;
        } catch (Throwable t) {
            PilotWatchLog.error("cc-transfer departure twin captain write FAILED (the own hull keeps"
                    + " the build-time captain until the BEGIN revert)", t);
            return false;
        }
    }

    /**
     * The stuck-B watchdog: last-ditch player restore for any non-IDLE
     * vanilla machine holding the player off the own hull while every designed lane is quiet
     * (no transit, no ack window, no abort return). The R7 wrap latch does NOT cover this
     * shape — a strand mints mid-round with {@code idleObservedThisRound} already true. Fires
     * after a {@link #STUCK_DWELL_MILLIS} continuous dwell; the action is a forced restore
     * through the validated in-play pick ({@link #inPlayRestoreTarget}, 0335-F1 — never onto a
     * dead own hull) + one loud log — B is NEVER touched (no state setter exists; writing
     * state is what strands). Called only with the machine read non-IDLE (placement in
     * {@link #pollLocalTrigger}).
     */
    private void maintainStuckWatchdog() {
        boolean lanesQuiet = !ownTransit && !awaitingBegin && !retargetAckPending
                && strandHiddenPod == null;
        ShipAPI player = lanesQuiet && plugin.engine != null
                ? plugin.engine.getPlayerShip() : null;
        boolean displaced = player != null
                && (player.isShuttlePod() || player != plugin.ownShip);
        if (!lanesQuiet || !displaced) {
            stuckSinceMillis = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (stuckSinceMillis == 0L) {
            stuckSinceMillis = now;
            return;
        }
        if (now - stuckSinceMillis > STUCK_DWELL_MILLIS) {
            ShipAPI restore = inPlayRestoreTarget(); // 0335-F1: never force onto a dead hull
            forcePlayerTo(restore); // null-safe no-op; the leave-loud case rides the log
            stuckSinceMillis = now; // re-dwell; the restored pointer clears `displaced` next read
            if (!stuckRestoreLogged) {
                stuckRestoreLogged = true;
                PilotWatchLog.error("cc-transfer stuck-B watchdog: vanilla machine non-IDLE"
                        + " with the player off the own hull and every lane quiet for "
                        + STUCK_DWELL_MILLIS + " ms -> "
                        + (restore != null ? "player forced home"
                                : "no in-play restore target — player left where they stand")
                        + " (B untouched; the machine settles on its own or the R7 wrap latch"
                        + " takes it next round)", null);
            }
        }
    }

    /**
     * A warroom (Tab) pick of the seat's OWN source
     * hull while its pod is in flight. Vanilla already turned the pod home (the engine's trigger
     * admits a pod-borne player re-picking its last ship), but the
     * server's flight row still flies to the granted target: the concealed-divergence class the
     * old bare {@code return} here produced ("never a server ask"). Send the SAME ask the typed
     * verb makes — {@code /transfer #<own wire id>}: the server resolves the seat's OWN wire
     * id to the verb (under the one-entity pod that id rides the census-unlisted POD,
     * so the OCCUPIED-by-self row it once keyed on no longer exists mid-flight) ⇒
     * {@code SeatRebindArbiter.transferReturnHome} ⇒ ABORT reason 14 ⇒ {@link #abortOwn} swaps
     * the seat back — once per episode (the {@code lastSentTargetWireId} gate). NOT an
     * ack-pending retarget: the answer is the ABORT itself, never a RETARGET BEGIN, so the
     * ack-timeout re-pin must not arm. A pre-BEGIN self-pick stays local (nothing to
     * return from).
     */
    private void maintainTabReturnHome() {
        if (!ownTransit) {
            return;
        }
        long own = plugin.session.shipId();
        if (own <= 0L || lastSentTargetWireId == own) {
            return;
        }
        adapter.setCrDeductEnabled(plugin.sideAuthority(), false); // the Tab click re-set it
        try {
            plugin.session.sendChat("/transfer #" + own);
            lastSentTargetWireId = own;
            PilotWatchLog.info("cc-transfer retarget: Tab pick of own source hull -> sent /transfer #"
                    + own + " (return-home ask, mid-flight)");
        } catch (Throwable t) {
            repinLocalMachine("return-home send FAILED (" + t + ")");
        }
    }

    /**
     * While the episode lives, mirror warroom retargets to the server: vanilla
     * already retargeted the LOCAL machine natively (B permits IN_FLIGHT), so the
     * server must re-point its flight row or the two worlds land on different hulls. The
     * change gate (TO != lastSent) is the only rate limit — a standing rule exempts same-seat
     * retargets from the server begin-floor, so no client debounce exists to fight the
     * warroom. A TO resolving to our own hull is the DENY/ABORT retarget-back flying home,
     * never a server ask.
     */
    private void maintainRetargetPoll() {
        if (homeFlightWatch || strandHiddenPod != null) {
            // The only TO changes during the post-abort
            // home flight / the strand's invisible landing are OURS (the watch's re-point) —
            // mirroring them minted the stray "/transfer #27" + "cooling down".
            return;
        }
        ShipAPI to = adapter.transferTarget(plugin.sideAuthority());
        if (to == null) {
            return;
        }
        if (returning && to == sourceHull) {
            // RC3's other half, narrowed: TO == the re-homed source IS our own
            // abort-time re-pin (the server already flies the pod home — a return-home ask now
            // is the stray "cooling down"). Any OTHER TO mid-return is a player warroom re-pick
            // the server honours in place (RETARGETED_RETURNING) — mirror it like any retarget,
            // or B aims at the new target while the wire pod docks home (a divergence).
            return;
        }
        if (to == sourceHull || to == plugin.ownShip) {
            if (to != sourceHull) {
                // On every module landing (alive or
                // dead source), vanilla's TO never flipped; OWN did. ModuleMountLane's own-mount
                // apply (OwnMountLifecycleLane.adoptOwnMountedModule) re-points plugin.ownShip
                // onto the LANDING TARGET (the module body) in the same frame, BEFORE this poll
                // and before our COMMIT ends the transit — so TO == ownShip here is the granted
                // target, not a Tab pick of the source. The ask it minted ("/transfer #<own>")
                // was the server's reason-13 "unknown target" chat error on all 4 module
                // landings. A home pick is TO == the pinned source hull, only.
                if (!landingPollNoted) {
                    landingPollNoted = true;
                    PilotWatchLog.info("cc-transfer retarget poll: TO == own body but not the"
                            + " source (own already re-pointed onto the landing target)"
                            + " -> no return-home ask");
                }
                return;
            }
            if (!to.isAlive()) {
                // A WRECK is never a return-home target (vanilla's
                // target guard + the server's reason-13 refusal). Stay local.
                return;
            }
            maintainTabReturnHome();
            return;
        }
        long wireId = plugin.wireIdForLocalShip(to);
        if (wireId <= 0L || wireId == plugin.session.shipId()
                || wireId == lastSentTargetWireId) {
            return;
        }
        adapter.setCrDeductEnabled(plugin.sideAuthority(), false); // the retarget click re-set it
        try {
            plugin.session.sendChat("/transfer #" + wireId);
            lastSentTargetWireId = wireId;
            if (ownTransit) {
                retargetAckPending = true;
                retargetSentAtMillis = System.currentTimeMillis();
            } else {
                awaitingBegin = true; // pre-BEGIN retarget = a fresh ask; re-arm the ack window
                triggerSentAtMillis = System.currentTimeMillis();
            }
            PilotWatchLog.info("cc-transfer retarget: sent /transfer #" + wireId
                    + (ownTransit ? " (mid-flight)" : " (pre-BEGIN)"));
        } catch (Throwable t) {
            // Fail-closed symmetric with the ack-timeout: collapse the local/server divergence
            // NOW by re-pinning the local machine to what the server actually flies.
            repinLocalMachine("retarget send FAILED (" + t + ")");
        }
    }

    /**
     * The retarget-denial collapse: re-pin the LOCAL vanilla machine to
     * the GRANTED target's replica body (never home — the server flight is still live and the
     * seat will land on ITS target). Retarget denials are chat-only server-side and the
     * denyOwn guard drops any that would arrive mid-transit (H1), so the ack-timeout/send-fail
     * callers here are the ONLY denial signal. With no granted flight (pre-BEGIN), home is the
     * only honest collapse.
     */
    private void repinLocalMachine(String why) {
        // An ABORTED flight has NO grant any more — the
        // "abort-then-completes" was this branch re-pinning the pod to the dead grant.
        if (grantedTargetWireId > 0 && !returning) {
            ShipAPI granted = replicaBodyOf(grantedTargetWireId);
            boolean repinned = granted != null
                    && adapter.retargetTo(plugin.sideAuthority(), granted);
            // The ask gate follows the LOCAL machine, not
            // the grant. A FAILED re-pin (replica unbound, or B at LANDING refusing the retarget)
            // leaves vanilla's TO where the last ask put it — the source for the return-home ask,
            // the new pick for a retarget — and re-pointing the gate to the grant anyway made the
            // poll re-mint that same ask every frame: "/transfer #<own>" → reason-13 deny →
            // re-pin → resend, one ask per deny RTT until the chat bucket dropped it.
            // Keeping the last ask as the gate makes TO == gate ⇒ the poll stays quiet; a NEW
            // pick still asks (gate != TO), and the COMMIT force owns the TO-vs-grant repair.
            if (repinned) {
                lastSentTargetWireId = grantedTargetWireId;
            }
            PilotWatchLog.info("cc-transfer " + why + " -> local machine re-pinned to GRANTED"
                    + " target " + grantedTargetWireId + " (repinned=" + repinned
                    + (granted == null ? "; replica unbound — the COMMIT force owns the repair"
                            : "")
                    + (repinned ? "" : "; ask gate KEPT at " + lastSentTargetWireId
                            + " (no re-ask of what B already flies)") + ")");
        } else {
            boolean retargeted = adapter.retargetToSource(plugin.sideAuthority());
            if (!retargeted) {
                armPendingCollapse(why + " (no granted flight)");
            }
            PilotWatchLog.info("cc-transfer " + why + " with no granted flight -> pod"
                    + " retargeted home (fail-closed, retargeted=" + retargeted + ")");
        }
    }

    private ShipAPI replicaBodyOf(long wireId) {
        PredictReconcileCombatPlugin.RemoteEntity row =
                plugin.remoteEntities.get(Long.valueOf(wireId));
        return row != null ? row.ship : null;
    }

    private void fold(ClientCommandTransfer e) {
        boolean own = plugin.session != null && e.seatWireId() == plugin.session.shipId();
        if (e.kind() == ClientCommandTransfer.KIND_BEGIN) {
            if (own) {
                beginOwn(e);
            } else {
                // Staged — the source re-home needs F's announced row (this frame's
                // RAPP fold, which runs AFTER this intake); capture the PRE-repoint variant now.
                Long seatKey = Long.valueOf(e.seatWireId());
                if (!stagedBeginVariant.containsKey(seatKey)) {
                    stagedBeginVariant.put(seatKey, plugin.variantById.get(seatKey));
                }
                staged.addLast(e);
            }
        } else if (e.kind() == ClientCommandTransfer.KIND_DENY) {
            if (own) {
                denyOwn(e);
            }
        } else if (e.kind() == ClientCommandTransfer.KIND_ABORT) {
            if (own) {
                abortOwn(e);
            } else {
                abortRemote(e);
            }
        } else if (e.kind() == ClientCommandTransfer.KIND_COMMIT) {
            if (own) {
                if (pendingOwnCommit != null && pendingOwnCommit.toWireId() != e.toWireId()) {
                    // Single-field stage drops the older commit — its adopt-hold stamp dies
                    // with it (mirror the abortOwn disarm), else the replaced id leaks a hold.
                    clearAdoptHold(pendingOwnCommit.toWireId());
                }
                pendingOwnCommit = e;
                commitWaitStartMillis = System.currentTimeMillis();
                rowlessParkStartMillis = 0L; // fresh commit = fresh park window
            } else {
                staged.addLast(e);
            }
        } else {
            anomaliesDropped++;
        }
    }

    private void beginOwn(ClientCommandTransfer e) {
        awaitingBegin = false;
        if (ownTransit) {
            if (strandPending) {
                // The server's pump strand + pod-body RESUME land at the SAME tick (a
                // retarget-lost row re-begins from the pod at the drain): the strand exit is
                // cancelled — the pod flies on toward this BEGIN's target; un-hide it.
                strandPending = false;
                if (localPod != null) {
                    try {
                        localPod.setExtraAlphaMult2(1f);
                    } catch (Throwable ignore) {
                        // presentation only
                    }
                }
                PilotWatchLog.info("cc-transfer BEGIN own after a same-tick STRAND: strand exit"
                        + " cancelled (pod-body RESUME toward " + e.toWireId() + ")");
            }
            if (e.toWireId() == grantedTargetWireId && !returning) {
                anomaliesDropped++; // double BEGIN — idempotency drop (H2 shape)
                return;
            }
            // While RETURNING, a BEGIN onto the ORIGINAL
            // grant (the owner re-picked the same destination mid-return) is the server's
            // RETARGETED_RETURNING fan, never an H2 double — the drop above swallowed it:
            // `returning` stayed true, the ack timed out into the home re-pin, the RC3 guard
            // then silenced the owner's next source pick (server flew on and COMMITTED), and the
            // post-COMMIT stale TO minted a fresh flight home = "two shuttlepods went back".
            // RETARGET BEGIN: the server re-fanned a flight-row target change (the
            // CTSL re-fan) — update the expectation; the local machine already retargeted
            // natively at the warroom click (or our own poll asked for exactly this). The pod
            // is never re-minted any more (ONE entity, S for the whole flight).
            grantedTargetWireId = e.toWireId();
            if (e.toWireId() == lastSentTargetWireId) {
                retargetAckPending = false; // our retarget ask was granted
            }
            // A RETURNING pod re-picked mid-return is RETARGETED in place
            // (server `TransferPodLane` RETARGETED_RETURNING → this BEGIN fan, via
            // `CommandTransferServeLane`) — the flight is target-bound again: no dock-back
            // COMMIT follows, the target COMMIT does. Leaving `returning` set kept the poll
            // silenced + the ack-timeout collapse aimed home while the server flew to the target.
            boolean wasReturning = returning;
            returning = false;
            PilotWatchLog.info("cc-transfer RETARGET BEGIN own: to=" + e.toWireId()
                    + " (pod = own seat " + podWireId + ")"
                    + (wasReturning ? " — RETURNING pod re-targeted in place; return ended" : ""));
            return;
        }
        long seat = plugin.session.shipId();
        if (e.podWireId() != seat) {
            // The one-entity server fans podWireId == seatWireId; anything else is a
            // two-entity server — unsupported. Proceed one-entity, LOUD (never a
            // silent divergence).
            PilotWatchLog.error("cc-transfer BEGIN own: podWireId=" + e.podWireId()
                    + " != seat " + seat + " — a two-entity server? proceeding one-entity", null);
        }
        ownTransit = true;
        observerMode = false;
        observerMintPending = false;
        grantedTargetWireId = e.toWireId();
        podWireId = seat; // S IS the pod
        beginFromWireId = e.fromWireId();
        // Captured BEFORE this frame's RAPP fold repoints the seat row to cc_pod (intake ≺
        // rapp.drain, the ordering law): the true source variant for the legacy
        // back-fill of F's row (the announced row wins, W3-I(b)).
        sourceVariant = plugin.variantById.get(Long.valueOf(seat));
        if (cc.starsector.client.net.ClientCommandTransfer.POD_WIRE_VARIANT_ID.equals(sourceVariant)
                && plugin.ownShip != null && plugin.ownShip.getVariant() != null) {
            //
            // When the RAPP and the BEGIN split across a render frame, the seat row already reads cc_pod; the live hull is the source.
            sourceVariant = plugin.ownShip.getVariant().getHullVariantId();
        }
        sourceReHomed = false;
        localPod = null;
        podSearchFrames = 0;
        sourceHull = plugin.ownShip; // re-homed under F at adopt (or removed on a RESUME)
        sourceIsClaimPlaceholder = plugin.claimPending(); // rider 7: a rejoin mid-transfer (R8)
        landingPollNoted = false;
        returning = false;
        strandPending = false;
        adapter.setCrDeductEnabled(plugin.sideAuthority(), false); // server owns CR
        PilotWatchLog.info("cc-transfer BEGIN own (one-entity): seat/pod=" + seat + " to="
                + e.toWireId() + " from=" + e.fromWireId()
                + (e.fromWireId() == seat ? " (RESUME from the stranded pod body)"
                        : " (source re-homes under F=" + e.fromWireId() + " at adopt)")
                + " -> TRANSIT (CR deduct off)");
    }

    private void denyOwn(ClientCommandTransfer e) {
        awaitingBegin = false;
        if (ownTransit) {
            if (e.reason() == ClientCommandTransfer.REASON_FRESH_DENIED && plugin.session != null
                    && lastSentTargetWireId == plugin.session.shipId()) {
                // The Tab return-home ask (/transfer
                // #<own>) drew the server's unknown-target deny (target 0, reason 13 — the
                // one-entity pod made the own id census-unlisted; the server now resolves it
                // as the verb, so only a flight-less race lands here). Vanilla already turned
                // the pod home while the server still flies the grant: fail-closed,
                // re-pin the local machine to the GRANTED target (the send-failure twin in
                // maintainTabReturnHome) — a silent drop left TO=source alive past the COMMIT,
                // which the retarget poll re-fired as a fresh "/transfer #40" (two pods home).
                repinLocalMachine("return-home DENIED reason=13");
                return;
            }
            // Retarget denials are chat-only server-side, so a DENY here is
            // malformed — NEVER loosen this guard to admit them (the ack-timeout re-pin is
            // the designed retarget-denial signal).
            anomaliesDropped++;
            return;
        }
        if (adapter.state(plugin.sideAuthority()) != TransferManagerAdapter.STATE_IDLE) {
            // IN_FLIGHT collapses home now; LANDING (or an unreadable
            // machine) defers to the pendingCollapse latch — acting at LANDING is the strand.
            boolean retargeted = adapter.retargetToSource(plugin.sideAuthority());
            if (!retargeted) {
                armPendingCollapse("DENY reason=" + e.reason());
            }
            PilotWatchLog.info("cc-transfer DENY own reason=" + e.reason() + (retargeted
                    ? " -> pod retargeted home (vanilla lands + restores the player natively)"
                    : " -> collapse DEFERRED (machine not IN_FLIGHT)"));
        } else {
            maintainConcealedSwapGuard("DENY reason=" + e.reason()); // Fast landing
        }
    }

    /**
     * Arm the deferred-collapse latch: a home-collapse was wanted but the
     * vanilla machine was not IN_FLIGHT (LANDING, or unreadable). Acting at LANDING is the
     * strand, so the latch waits it out; {@link #maintainPendingCollapse} pumps.
     */
    private void armPendingCollapse(String why) {
        if (!pendingCollapse) {
            pendingCollapse = true;
            PilotWatchLog.info("cc-transfer collapse DEFERRED (" + why + "): machine not"
                    + " IN_FLIGHT — a typed retarget at LANDING strands; latch"
                    + " pumps in applyStaged");
        }
    }

    /**
     * The deferred-collapse latch pump (runs at !ownTransit): LANDING ⇒
     * keep waiting (the landing exits on its own — B has no abort); IN_FLIGHT ⇒ the deferred
     * home-retarget acts now; IDLE ⇒ the landing completed on the denied body — the
     * concealed-swap force repairs the player pointer (a no-op when no swap happened);
     * UNKNOWN ⇒ adapter dead, disarm. The IDLE disarm samples every frame, so a stale latch
     * cannot survive into a later episode.
     */
    private void maintainPendingCollapse() {
        int state = adapter.state(plugin.sideAuthority());
        if (state == TransferManagerAdapter.STATE_LANDING) {
            return; // still landing — acting now strands; wait for the machine to exit
        }
        if (state == TransferManagerAdapter.STATE_IN_FLIGHT) {
            if (adapter.retargetToSource(plugin.sideAuthority())) {
                pendingCollapse = false;
                PilotWatchLog.info("cc-transfer deferred collapse: machine back IN_FLIGHT"
                        + " -> pod retargeted home");
            }
            return; // false (FROM lost / raced): hold the latch and retry next frame
        }
        pendingCollapse = false; // IDLE or UNKNOWN — the latch's wait is over either way
        if (state == TransferManagerAdapter.STATE_IDLE) {
            maintainConcealedSwapGuard("deferred collapse landed IDLE");
        }
    }

    /**
     * The concealed-swap guard: a near target can vanilla-land in under the ack
     * window, so a DENY / BEGIN-ack timeout / late ABORT can arrive with the
     * local machine already IDLE and the player silently swapped onto the server-DENIED
     * target's replica. Only the player pointer moved — the replica body is untouched
     * row-truth — so forcing it home is the complete repair.
     */
    private void maintainConcealedSwapGuard(String why) {
        if (plugin.ownShip == null || plugin.engine == null) {
            return;
        }
        ShipAPI player = plugin.engine.getPlayerShip();
        if (player != null && player != plugin.ownShip) {
            ShipAPI restore = inPlayRestoreTarget(); // 0335-F1: never force onto a dead hull
            if (restore != null) {
                forcePlayerTo(restore);
                PilotWatchLog.error("cc-transfer concealed-swap guard (" + why + "): the local"
                        + " machine landed on an ungranted body — player forced back to "
                        + (restore == plugin.ownShip ? "the own hull"
                                : "the transit source hull"), null);
            } else {
                PilotWatchLog.error("cc-transfer concealed-swap guard (" + why + "): the local"
                        + " machine landed on an ungranted body and no in-play restore target"
                        + " exists — player left where they stand", null);
            }
        }
    }

    private void abortOwn(ClientCommandTransfer e) {
        if (!ownTransit) {
            if (adapter.state(plugin.sideAuthority()) == TransferManagerAdapter.STATE_IDLE) {
                // A post-local-landing ABORT — the fast-landing concealed-swap class.
                maintainConcealedSwapGuard("late ABORT reason=" + e.reason());
            } else {
                anomaliesDropped++;
            }
            return;
        }
        if (pendingOwnCommit != null) {
            clearAdoptHold(pendingOwnCommit.toWireId()); // F1: no adopt coming for this id
        }
        pendingOwnCommit = null;
        // The abort ends any retarget ask — a pending ack must never time out
        // into the GRANTED-target re-pin (that pin is what seated the owner on the citadel).
        retargetAckPending = false;
        // from-law: from == F ⇒ RETURNING (the server flies the pod home over real
        // distance; the dock-back COMMIT with to == F ends the transit); from == S ⇒ STRANDED.
        boolean isReturning = e.fromWireId() != e.seatWireId();
        if (isReturning) {
            returning = true;
            boolean repinned = false;
            if (localPod != null && sourceHull != null && sourceHull.isAlive()
                    && plugin.engine != null && plugin.engine.isEntityInPlay(sourceHull)) {
                // Re-pin B's landing key onto the re-homed source body (vanilla has NO
                // return-home branch, the re-invoke is REQUIRED); at LANDING the
                // retarget is refused — the latch picks the player off a wrong swap.
                repinned = adapter.retargetTo(plugin.sideAuthority(), sourceHull);
                if (!repinned) {
                    armPendingCollapse("ABORT own RETURNING reason=" + e.reason());
                }
            }
            PilotWatchLog.info("cc-transfer ABORT own RETURNING reason=" + e.reason() + " from="
                    + e.fromWireId() + " repinned=" + repinned
                    + " -> transit KEPT; the dock-back COMMIT ends it");
            return;
        }
        // STRANDED — the invisible-landing exit: B needs a landing to reach IDLE
        // (a flip-in-place bricks the warroom button for the mission AND leaves a latent
        // landing onto the dead target hulk), so hide the local pod NOW and let the next
        // snapshot (pinPump: it needs the S pose) mint the cc_pod own body at rest + land the
        // hidden pod onto it. Observer / no-local-pod strands mint + force only.
        strandPending = true;
        strandReason = e.reason();
        hideStrandedPod(localPod);
        PilotWatchLog.info("cc-transfer ABORT own STRANDED reason=" + e.reason()
                + " -> invisible-landing exit armed (own body mints at the S pose next snapshot)");
    }

    /** {@code extraAlphaMult2 = 0} hides hull sprite + engine glow —
     *  the render multiplies BOTH by it UNCONDITIONALLY AFTER the
     *  animated-launch/landing block that OVERWRITES {@code combinedAlphaMult} from
     *  {@code sinceLaunch} (so the earlier {@code setAlphaMult(0)} was discarded for
     *  the whole 3 s landing). Re-asserted per frame by the home-flight watch
     *  until B reaches IDLE (B removes the pod there — nothing to restore). */
    private void hideStrandedPod(ShipAPI pod) {
        if (pod == null) {
            return;
        }
        try {
            pod.setExtraAlphaMult2(0f);
        } catch (Throwable ignore) {
            // presentation only; a visible landing pod is jank, never a divergence
        }
    }

    /**
     * STRANDED exit, run at the first snapshot after the abort (pinPump): (ii) mint
     * the cc_pod own body AT the S pose at rest ({@link OwnSeatLifecycleLane#respawnOwnAtRest}
     * — the S row's variant is already cc_pod via the RAPP repoint); (iii) re-point B's landing
     * key onto it (dist ≈ 0 ⇒ immediate {@code beginLandingAnimation}, 3 s, then IDLE +
     * {@code removeObject(pod)} + an idempotent {@code setPlayerShip(newOwn)}); (iv) force the
     * player onto it NOW + clearTransit (prediction unparked — the own reconcile drives the
     * cc_pod body; the server unparked S the same tick); (v) the home-flight watch stays the belt
     * (IN_FLIGHT ⇒ TO held on the own body). Returns false while the S pose is absent (retry).
     */
    private boolean runStrandExit(ServerSnapshotFrame snapshot) {
        ServerEntityPose pose = snapshot.entityById(plugin.session.shipId());
        if (pose == null || pose.hitpoints() <= 0f) {
            if (++podSearchFrames > POD_SEARCH_FRAME_BUDGET * 2) {
                // The stranded pod never showed alive on the wire — the seat died with it.
                // Own death rides the normal edge; end transit honestly.
                strandPending = false;
                endTransitRestore("ABORT stranded, S pose never alive (reason=" + strandReason + ")");
                return true;
            }
            return false;
        }
        strandPending = false;
        ShipAPI pod = localPod;
        ShipAPI newOwn;
        ShipAPI obs = observerBody;
        if (obs != null && obs.isAlive() && plugin.engine != null
                && plugin.engine.isEntityInPlay(obs)) {
            // Observer mode: the S-row cc_pod own body ALREADY stands at the S
            // pose (same mint path, pinned from the S row) — it becomes the own hull outright.
            // The earlier shape minted a SECOND body and removed `plugin.ownShip`, which is the
            // re-homed SOURCE (F's replica) while the observer mint is still owed.
            newOwn = obs;
            plugin.ownShip = obs;
        } else {
            ShipAPI stale = plugin.ownShip; // B's pod (B removes it at its landing) — never removed here
            plugin.ownSeat.respawnOwnAtRest(pose);
            newOwn = plugin.ownShip;
            if (newOwn == null || newOwn == stale) {
                // Bounded like the pose-absent exit above (shared budget) + a first-fail log
                // only (the unbudgeted retry logged per snapshot).
                if (++podSearchFrames > POD_SEARCH_FRAME_BUDGET * 2) {
                    PilotWatchLog.error("cc-transfer ABORT stranded: own cc_pod mint FAILED for "
                            + podSearchFrames + " snapshots -> transit ended honestly", null);
                    endTransitRestore("ABORT stranded, own mint failed (reason=" + strandReason + ")");
                    return true;
                }
                if (podSearchFrames == 1) {
                    PilotWatchLog.error("cc-transfer ABORT stranded: own cc_pod mint FAILED at the"
                            + " S pose -> retrying (bounded)", null);
                }
                strandPending = true;
                return false;
            }
        }
        boolean repointed = false;
        if (pod != null) {
            strandHiddenPod = pod;
            hideStrandedPod(pod);
            repointed = adapter.retargetTo(plugin.sideAuthority(), newOwn);
            armHomeFlightWatch("ABORT own STRANDED (invisible landing onto the own cc_pod body)");
        }
        forcePlayerTo(newOwn); // respawnOwnAtRest already re-pointed; idempotent belt
        PilotWatchLog.info("cc-transfer ABORT stranded (reason=" + strandReason + "): own cc_pod"
                + " body minted AT REST at the S pose (" + pose.locX() + "," + pose.locY() + ")"
                + (pod != null ? "; hidden local pod re-pointed onto it (repointed=" + repointed
                        + ") for the invisible landing" : "; no local pod (observer)")
                + " -> transit END");
        clearTransit("ABORT stranded");
        return true;
    }

    /** Per-frame heavy pump — after spawnRemoteEntities (bodies exist to adopt/re-point). */
    void applyStaged() {
        if (plugin.session == null) {
            return;
        }
        if (awaitingBegin && !ownTransit
                && System.currentTimeMillis() - triggerSentAtMillis > BEGIN_ACK_MILLIS) {
            awaitingBegin = false;
            if (adapter.state(plugin.sideAuthority()) != TransferManagerAdapter.STATE_IDLE) {
                // A 3-way split (the E1 strand site): IN_FLIGHT collapses home now; LANDING
                // arms the latch — the typed retarget mid-landing is what stranded E1.
                boolean retargeted = adapter.retargetToSource(plugin.sideAuthority());
                if (!retargeted) {
                    armPendingCollapse("BEGIN-ack timeout");
                }
                PilotWatchLog.info("cc-transfer trigger: no server BEGIN/DENY within "
                        + BEGIN_ACK_MILLIS + " ms -> " + (retargeted
                                ? "pod retargeted home (fail-closed; transfer.enabled off,"
                                        + " or the ask was lost)"
                                : "collapse DEFERRED (machine not IN_FLIGHT)"));
            } else {
                maintainConcealedSwapGuard("BEGIN-ack timeout"); // Fast landing
            }
        }
        if (retargetAckPending && ownTransit
                && System.currentTimeMillis() - retargetSentAtMillis > BEGIN_ACK_MILLIS) {
            // Retarget denials are chat-only — the missing RETARGET BEGIN is the
            // ONLY denial signal, and the collapse is a re-pin to the GRANTED target, never home.
            retargetAckPending = false;
            repinLocalMachine("retarget ack-timeout (denied or lost)");
        }
        if (pendingCollapse && !ownTransit) {
            maintainPendingCollapse(); // The deferred-collapse latch pump
        }
        if (homeFlightWatch && !ownTransit) {
            maintainHomeFlightWatch(); // The post-abort pod flies HOME, not to 27
        }
        maintainIdleDivergenceGuard(); // FLOOR: player pointer == own body at IDLE
        if (ownTransit && !observerMode && localPod == null) {
            maintainLocalPodSynth(); // Typed /transfer — drive the machine
            adoptLocalPod();
        }
        if (pendingOwnCommit != null && !strandPending) {
            maintainOwnCommit();
        }
        for (int i = staged.size(); i > 0; i--) {
            ClientCommandTransfer e = staged.pollFirst();
            boolean consumed;
            try {
                consumed = e.isBegin() ? applyRemoteBegin(e) : applyRemoteCommit(e);
            } catch (Throwable t) {
                anomaliesDropped++;
                consumed = true; // consumed-with-error: a poison COMMIT never retries forever
                PilotWatchLog.error("cc-transfer remote COMMIT apply FAILED (consumed-with-error)"
                        + " seat=" + e.seatWireId() + " to=" + e.toWireId(), t);
            }
            if (!consumed) {
                staged.addLast(e); // park-and-retry: the target replica is not bound yet
            } else {
                clearAdoptHold(e.toWireId()); // F1: applied or dropped — stamp dies with it
            }
        }
    }

    /**
     * A typed {@code /transfer} has no local vanilla episode:
     * the own BEGIN arrives with the player still mounted on the source hull and the vanilla
     * machine IDLE. Drive the machine's spawn branch ourselves (typed == clicked) via
     * {@link TransferManagerAdapter#driveSpawn}: FROM = the own hull passed explicitly, TO =
     * the granted target's replica body. The trigger pre-sets
     * ({@code triggerSentThisEpisode}/{@code lastSentTargetWireId}) keep the poll and the
     * retarget-mirror lanes quiet — the server already granted exactly this flight; never arm
     * {@code awaitingBegin} (ownTransit already routes {@code pollLocalTrigger} away). The
     * minted pod becomes the player ship synchronously, so {@link #adoptLocalPod}
     * adopts it in this same pass and observer mode never fires. Park-and-retry until the
     * replica binds (the {@code applyOwnCommit} mirror); observer stays the last resort
     * (machine non-IDLE — strict ==IDLE gate, a bricked R7 machine must never be driven;
     * replica never bound/dead/out-of-play within the search budget; own hull null/dead [H];
     * a silent B guard-ladder no-op).
     */
    private void maintainLocalPodSynth() {
        if (synthDriven || !adapter.usable()) {
            return;
        }
        ShipAPI player = plugin.engine != null ? plugin.engine.getPlayerShip() : null;
        if (player != null && player.isShuttlePod()) {
            return; // a local pod already exists — adopt, never synth (the clicked path)
        }
        // A DEAD own is still a launch site. Vanilla's
        // own campaign death-transfer passes the hulk as `from` (getPlayerShip() stays the hulk
        // after death); the launch routine reads `from` for facing/location + setLaunchingShip only
        // and CombatState.setPlayerShip skips the AI-restore for a hulk source. The former
        // `!isAlive()` bail here + the own-pod replica suppression left the wreck-source flight
        // POD-LESS (121 frames -> OBSERVER -> instant COMMIT = "teleport"). Only a NULL own has
        // no launch site; a removed wreck still spawns at its last location.
        if (plugin.ownShip == null) {
            return; // no source object at all — the search budget walks to observer
        }
        if (adapter.state(plugin.sideAuthority()) != TransferManagerAdapter.STATE_IDLE) {
            return; // a live or bricked machine is never driven (gate ==IDLE, NOT !=UNKNOWN)
        }
        ShipAPI target = replicaBodyOf(grantedTargetWireId);
        if (target == null || !target.isAlive() || plugin.engine == null
                || !plugin.engine.isEntityInPlay(target)) {
            return; // park-and-retry: spawn-on-sight binds the replica a frame later
        }
        triggerSentThisEpisode = true;              // pre-set BEFORE driving: no trigger send
        lastSentTargetWireId = grantedTargetWireId; // and no retarget mirror for this flight
        boolean drove = adapter.driveSpawn(plugin.sideAuthority(), plugin.ownShip, target);
        synthDriven = drove; // a false drive (transient tm/race) retries within the budget
        PilotWatchLog.info("cc-transfer own BEGIN with no local pod -> vanilla spawn DRIVEN"
                + " (typed == clicked) target=" + grantedTargetWireId
                + " drove=" + drove + " hulkSource=" + !plugin.ownShip.isAlive());
    }

    private void adoptLocalPod() {
        podSearchFrames++;
        // Vanilla made OUR pod the player ship at trigger — the unambiguous read;
        // remote seats' pod REPLICAS are never the player ship (bank d disambiguation).
        ShipAPI player = plugin.engine != null ? plugin.engine.getPlayerShip() : null;
        if (player != null && player.isShuttlePod() && player.isAlive()) {
            localPod = player;
            switchOwnOntoPod(player);
            PilotWatchLog.info("cc-transfer pod ADOPTED: the local vanilla pod IS the own body"
                    + " (seat " + podWireId + "); AI kept, pose pinned to the S row");
            return;
        }
        if (podSearchFrames > POD_SEARCH_FRAME_BUDGET) {
            observerMode = true; // late-join / no-local-pod own BEGIN
            observerMintPending = true; // pinPump mints the cc_pod own body from the S row
            switchOwnOntoPod(null);
            PilotWatchLog.info("cc-transfer own BEGIN with NO local pod after " + podSearchFrames
                    + " frames -> OBSERVER (cc_pod own body mints from the S row next snapshot;"
                    + " COMMIT applies instantly)");
        }
    }

    /**
     * The ONE-ENTITY identity switch at adopt (runs post-RAPP, so F's announced row
     * exists): the vacated body re-homes under F NOW ({@link #reHomeSourceUnder}, moved here from
     * the COMMIT source-half; the W3-I(b) "announced row wins" bind is unchanged) — UNLESS the
     * BEGIN was a RESUME ({@code from == S}): the previous own body is the stranded cc_pod body
     * the local vanilla pod visually replaces, so it is simply removed. Then the pod (or, in
     * observer mode, nothing yet — the S-row mint follows in pinPump) becomes
     * {@code plugin.ownShip}; {@code sourceHull} keeps the re-homed body ONLY as B's landing key
     * for RETURNING. Rider (h)(iv): a DEAD source (F2 from a wreck) binds its hulk into F's row.
     */
    private void switchOwnOntoPod(ShipAPI pod) {
        long seat = plugin.session.shipId();
        ShipAPI vacated = sourceHull;
        if (!sourceReHomed) {
            sourceReHomed = true;
            if (sourceIsClaimPlaceholder) {
                // A rejoin MID own-transfer — the
                // server's session_live catch-up replays the live BEGIN
                // (CommandTransferServeLane.replayTransfersToSession) while the claim-at-play
                // placeholder is still the own body (or nothing is bound yet), so beginOwn
                // captured the PLACEHOLDER as the source. It is not F's vacated hull: re-homing
                // it under F replaced the wire-minted true replica (reHomeSourceUnder removes
                // priorRow.ship) with a hull of the placeholder's variant at the deploy point,
                // visible until it died; an uncaptured one was orphaned as a live AI hull. Remove
                // it like the RESUME arm's stranded body — F's body is the RAPP-announced replica
                // (spawn-on-sight), which is also B's landing key for a RETURNING abort when it is
                // bound (else the pending-collapse latch, fail-closed). The claim itself settles
                // after the COMMIT (adopt-in-place on the target body; the gate holds on transit).
                ShipAPI placeholder = vacated != null ? vacated : plugin.ownShip;
                if (placeholder != null && placeholder != pod) {
                    if (plugin.engine != null && plugin.engine.isEntityInPlay(placeholder)) {
                        plugin.removeStaleBody(placeholder, "transfer-claim-placeholder"); //
                    }
                    plugin.forgetRemoteBodyTreatment(placeholder);
                    plugin.seatHud.forgetSeatName(placeholder);
                }
                sourceHull = beginFromWireId > 0 && beginFromWireId != seat
                        ? replicaBodyOf(beginFromWireId) : null;
                PilotWatchLog.info("cc-transfer BEGIN own under a PENDING CLAIM (rejoin mid-transfer,"
                        + " R8): the claim-at-play placeholder " + (placeholder != null && placeholder != pod
                                ? "removed (never F's body)" : "was not bound") + "; F="
                        + beginFromWireId + " landing key = " + (sourceHull != null
                                ? "the wire-minted replica" : "UNBOUND (pending-collapse on a return)"));
            } else if (vacated != null && vacated != pod) {
                if (beginFromWireId > 0 && beginFromWireId != seat) {
                    String sourceCaptain = reHomeSourceUnder(beginFromWireId, vacated, sourceVariant);
                    PilotWatchLog.info("cc-transfer BEGIN own: vacated "
                            + (vacated.isAlive() ? "hull" : "WRECK") + " re-homed under F="
                            + beginFromWireId + " (variant " + FallbackAnnounce.sanitize(sourceVariant, 64) + ")"
                            + " sourceCaptain=" + sourceCaptain);
                } else {
                    // RESUME: the stranded cc_pod body — the flying pod replaces it 1:1
                    if (plugin.engine != null && plugin.engine.isEntityInPlay(vacated)) {
                        plugin.removeStaleBody(vacated, "transfer-resume-stranded-pod"); //
                    }
                    plugin.forgetRemoteBodyTreatment(vacated);
                    sourceHull = null; // nothing to return to
                    PilotWatchLog.info("cc-transfer BEGIN own RESUME: the stranded cc_pod body"
                            + " removed (the local pod IS the seat now)");
                }
            }
        }
        if (pod != null) {
            plugin.ownShip = pod;
            plugin.ownLife.rearmOwnPoseBaselines();
            plugin.seatHud.applySeatName(pod, seat); // rider (h)(v): the seat tag rides the pod
        }
        // observer: plugin.ownShip keeps the vacated body (now F's neutralized remote — pose
        // writes are ownTransit-gated) for the one frame until the S-row mint lands in pinPump;
        // nulling it would trip every "no own hull" lane in the plugin for nothing.
    }

    private void maintainOwnCommit() {
        ClientCommandTransfer e = pendingOwnCommit;
        boolean leftPod;
        if (observerMode || localPod == null) {
            leftPod = true; // no local swap coming — apply instantly
        } else {
            ShipAPI player = plugin.engine != null ? plugin.engine.getPlayerShip() : null;
            // When the player ship is no longer the pod, vanilla swapped — possibly
            // onto a stale object; the adopt's unconditional setPlayerShipExternal repairs that
            // (a no-op when identical).
            leftPod = player != localPod || !localPod.isAlive();
        }
        boolean timedOut =
                System.currentTimeMillis() - commitWaitStartMillis > SWAP_FORCE_MILLIS;
        if (!leftPod && !timedOut) {
            return; // bounded wait for the local vanilla landing swap (anim 3.0 s)
        }
        boolean consumed;
        try {
            consumed = applyOwnCommit(e);
        } catch (Throwable t) {
            anomaliesDropped++;
            consumed = true;
            PilotWatchLog.error("cc-transfer own COMMIT apply FAILED (consumed-with-error)"
                    + " to=" + e.toWireId() + " from=" + e.fromWireId(), t);
            endTransitRestore("own COMMIT failed"); // @0334: anomaly exits must restore
        }
        if (consumed) {
            clearAdoptHold(e.toWireId()); // F1: consumed (adopted or dropped) — stamp dies too
            pendingOwnCommit = null;
        }
        // not consumed: the target replica is unbound — retry next frame (the wait has elapsed)
    }

    private boolean applyOwnCommit(ClientCommandTransfer e) {
        Long toKey = Long.valueOf(e.toWireId());
        PredictReconcileCombatPlugin.RemoteEntity rowB = plugin.remoteEntities.get(toKey);
        if (rowB == null) {
            // W3-J(i): a module-target COMMIT pairs with a KIND_MOUNT the MMNT lane
            // applied FIRST this frame (mmnt.drain ≺ xfer.applyStaged) — the mount un-rowed the
            // target id and adopted its body as own, and that id NEVER re-rows (it re-keys
            // fresh at dismount), so a park would wait forever. The target adopt is MMNT's;
            // the SOURCE re-home is OURS (the old "RAPP announce fold owns it"
            // reading was fiction: RosterAppendLane creates the ROW only, nothing binds the
            // live body, so spawn-on-sight minted a TWIN into the announced row while the
            // pinned original sat removed-but-deployed under the lock).
            ModuleMountLane.MountedSeat mounted =
                    plugin.mountedSeats.get(Long.valueOf(plugin.session.shipId()));
            if (mounted != null && mounted.own && mounted.body != null
                    && mounted.body == plugin.ownShip && mounted.body.isAlive()) {
                // The source re-home already ran at BEGIN (switchOwnOntoPod) —
                // nothing source-side is owed here; the mount owns the adopt. The observer
                // cc_pod body (no B to remove it) is dropped like the plain path below.
                dropObserverBody(mounted.body);
                PilotWatchLog.info("cc-transfer own COMMIT: toWireId=" + e.toWireId()
                        + " is a mounted station module (MMNT owns the adopt; source re-homed"
                        + " at BEGIN) - transit ended, no drop");
                clearTransit("own COMMIT module-mounted");
                holdStaleTargetAfterCommit();
                return true;
            }
            // Non-module rowless: a spawn-on-sight/RAPP-fold row can be frames away — park
            // bounded instead of insta-dropping. A
            // retired id is a known-permanent absence (H2 double delivery) — no park.
            boolean retired = retiredWireIds.contains(toKey);
            if (!retired) {
                if (rowlessParkStartMillis == 0L) {
                    rowlessParkStartMillis = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - rowlessParkStartMillis
                        <= ROWLESS_PARK_MILLIS) {
                    return false; // park-and-retry (maintainOwnCommit pumps every frame)
                }
            }
            anomaliesDropped++;
            PilotWatchLog.error("cc-transfer own COMMIT: no registry row for toWireId="
                    + e.toWireId() + (retired
                            ? " (already retired — double delivery, H2)"
                            : " (dropped after " + ROWLESS_PARK_MILLIS + " ms park)"), null);
            endTransitRestore("own COMMIT rowless"); // @0334: anomaly exits must restore
            return true;
        }
        ShipAPI targetBody = rowB.ship;
        if (targetBody == null) {
            if (rowB.killed) {
                anomaliesDropped++;
                PilotWatchLog.error("cc-transfer own COMMIT: target row " + e.toWireId()
                        + " is killed+unbound — nothing to adopt (dropped)", null);
                endTransitRestore("own COMMIT dead target"); // @0334: anomaly exits must restore
                return true;
            }
            return false; // park-and-retry until spawn-on-sight binds the target
        }
        // ---- capture BEFORE any rewrite (capture-then-rewrite) ----
        ShipAPI oldOwn = plugin.ownShip;
        Long seatKey = Long.valueOf(plugin.session.shipId());
        String targetVariant = plugin.variantById.get(toKey);
        // ---- (A) defuse-first the RETIRING target row (MMNT's order), retire the id ----
        rowB.capActive = false;
        rowB.ship = null;
        plugin.remoteEntities.remove(toKey);
        plugin.variantById.remove(toKey);
        retiredWireIds.add(toKey); // H1: a stale pre-COMMIT snapshot must never re-mint it
        // ---- adopt: the target body becomes the OWN hull (OML transfer subset — no heal) ----
        plugin.unregisterReplayDriven(targetBody);
        plugin.forgetRemoteBodyTreatment(targetBody);
        plugin.ownLife.adoptOwnTransferHull(targetBody, targetVariant);
        // W3-J CLIENT ROOT: the adopted body was a client-local-AI-flown
        // replica — its LOCAL retreat leftovers (Ship flags + a possible task-manager RETREAT
        // assignment) survive the adopt, and the bit-9 emitter samples isRetreating() on exactly
        // this body ⇒ a spurious TRUE edge crosses the wire and re-engages the
        // server drive. Clear drive-first (the engine's guard refuses a flag-clear
        // while the drive is on), cancel the assignment, clear the flags — all public ShipAPI /
        // CombatTaskManagerAPI verbs (B-legal, no engine-typed cast). The drive-off one-frame
        // latency can no-op the same-frame flag clear — plugin.ownAdoptRetreatClearLeft re-runs
        // the clear for a few frames until isRetreating() reads false (PRCP advance tail).
        // The API removeAssignment was a NO-OP on a direct RETREAT order
        // — the engine-internal cancel in ReplicaOrderSweep removes it and logs the tuple it found
        // (the server quarantine belts the edge if the clear throws).
        try {
            targetBody.turnOffTravelDrive();
            plugin.orderSweep.clearRetreat(plugin.engine, targetBody, "adopt", toKey.longValue(), false);
            targetBody.setRetreating(false, false);
            plugin.ownAdoptRetreatClearLeft = 10;
        } catch (Throwable t) {
            PilotWatchLog.error("cc-transfer own COMMIT: adopt retreat-clear failed"
                    + " (commit proceeds; the server quarantine still belts"
                    + " the edge)", t);
        }
        if (targetVariant != null) {
            plugin.variantById.put(seatKey, targetVariant);
        }
        // ---- (B) GONE: the source re-homed under F at BEGIN (switchOwnOntoPod);
        // `from` is ignored here (F, or S on a dock-back / RESUME). The old own body is B's
        // local pod (B removes it at its landing) or the observer cc_pod body (dropped here).
        dropObserverBody(targetBody);
        adapter.setLastShip(plugin.sideAuthority(), targetBody); // vanilla last-ship pointer
        commitsApplied++;
        PilotWatchLog.info("cc-transfer own COMMIT applied: own -> wire " + e.toWireId()
                + " body (seat id unchanged; from=" + e.fromWireId()
                + (e.toWireId() == beginFromWireId ? ", DOCK-BACK onto the re-homed source" : "")
                + "; old own=" + (oldOwn == localPod ? "B's pod" : "observer body")
                + ") (applied=" + commitsApplied + ")");
        clearTransit("own COMMIT");
        holdStaleTargetAfterCommit();
        return true;
    }

    /**
     * A vanilla warroom TO that PREDATES the COMMIT is
     * not a new pick. B's pod may still be flying (a home re-pin, an un-mirrored pick) after our
     * COMMIT swapped own onto the target, so the machine reads non-IDLE and
     * {@link #maintainRetargetPoll} would mirror that stale TO as a fresh "/transfer #N
     * (pre-BEGIN)" 4 ms after the commit (the second pod of the two-pod episode). Hold it as
     * already-sent: the poll's change gate ignores it; a NEW warroom pick changes TO and mirrors
     * normally; IDLE resets the gate ({@link #pollLocalTrigger}).
     */
    private void holdStaleTargetAfterCommit() {
        try {
            ShipAPI to = adapter.transferTarget(plugin.sideAuthority());
            long wireId = to != null ? plugin.wireIdForLocalShip(to) : 0L;
            if (wireId > 0L && plugin.session != null && wireId != plugin.session.shipId()) {
                lastSentTargetWireId = wireId;
                PilotWatchLog.info("cc-transfer post-COMMIT: vanilla TO still names wire " + wireId
                        + " (a pre-COMMIT pick) -> HELD, not a fresh ask (a NEW warroom pick"
                        + " re-arms the mirror)");
            }
        } catch (Throwable t) {
            PilotWatchLog.error("cc-transfer post-COMMIT stale-TO hold failed (poll unguarded)", t);
        }
    }

    /** The observer-mode cc_pod own body has no vanilla B to remove it at a landing — drop it at
     *  COMMIT (never B's pod: that removal bricks B; never the adopted body). */
    private void dropObserverBody(ShipAPI adopted) {
        ShipAPI body = observerBody;
        observerBody = null;
        if (body != null && body != adopted && body != localPod && plugin.engine != null
                && plugin.engine.isEntityInPlay(body)) {
            plugin.removeStaleBody(body, "transfer-observer-body"); // Evict + remove
        }
    }

    /**
     * The source own-body -> FRESH remote row keyed
     * {@code fromWireId}: binds {@code body} (the LIVE vacated hull) into the row there — the
     * server's source-identity RAPP append when announced (W3-I(b)), else a fresh mint; a
     * pose-raced spawn-on-sight mint in that row is dropped first (true body in hand). Shared
     * by the plain own-COMMIT ELSE arm and the module-mounted early return (W3-S: that path
     * used to end transit with NO re-home — the vacated source stayed an unrowed engine body
     * under the pin while a TWIN minted into the announced row).
     * ORDER LAW: {@code setControlsLocked(false)} runs BEFORE neutralize —
     * registerReplayDriven's first-treatment branch decides the lock afresh on bays&gt;0 bodies
     * (a FOE carrier, or any carrier while the window's bay kill switch is un-latched, re-locks;
     * an OWN-team carrier under the live latch stays UNLOCKED so the vacated hull remains a
     * transfer target), and clear-then-neutralize is what lets that decision stand.
     * {@code legacyVariant} back-fills variantById only when the announced row didn't
     * (announced wins — the own-twin mislabel); the module-mounted caller passes
     * null (its seat row was already re-pointed to the MODULE variant by the mount).
     * Returns the BEGIN captain revert's token ({@link #revertVacatedCaptain}, run first: the
     * hull the seat leaves gets its own original captain back, the server's twin — for the
     * claim's in-place-adopted body, the own lane's remembered seat twin).
     */
    private String reHomeSourceUnder(long fromWireId, ShipAPI body, String legacyVariant) {
        String sourceCaptain = revertVacatedCaptain(body, plugin.ownSeat.adoptedOriginalOrNull(body));
        Long fromKey = Long.valueOf(fromWireId);
        PredictReconcileCombatPlugin.RemoteEntity priorRow = plugin.remoteEntities.get(fromKey);
        PredictReconcileCombatPlugin.RemoteEntity fresh;
        if (priorRow != null) {
            if (priorRow.ship != null && priorRow.ship != body) {
                if (priorRow.ship.isAlive() && plugin.engine != null) {
                    // pose-raced mint — no wire feed will ever drive it again
                    plugin.removeStaleBody(priorRow.ship, "transfer-prior-row-remint"); //
                }
                plugin.forgetRemoteBodyTreatment(priorRow.ship);
            }
            fresh = priorRow;
            if (fresh.ship == null) {
                plugin.remoteUnbound = Math.max(0, plugin.remoteUnbound - 1);
            }
            fresh.ship = body;
        } else {
            fresh = new PredictReconcileCombatPlugin.RemoteEntity(fromWireId, 0);
            fresh.ship = body;
            plugin.remoteEntities.put(fromKey, fresh);
        }
        fresh.rekeyPoseHold = true; // first post-re-key pose is ~1 tick out
        fresh.rekeyHoldSinceTick = Long.MIN_VALUE;
        fresh.killed = !body.isAlive(); // A WRECK source (F2) binds as a dead row
        if (legacyVariant != null && !plugin.variantById.containsKey(fromKey)) {
            plugin.variantById.put(fromKey, legacyVariant); // legacy only — announced wins
        }
        try {
            body.setControlsLocked(false); // clean baseline; neutralize re-locks carriers
        } catch (Throwable ignore) {
        }
        // The vacated hull sheds any direct RETREAT order + retreat flag
        // (the engine gave it a live BasicShipAI from the X press until here).
        plugin.orderSweep.clearRetreat(plugin.engine, body, "re-home", fromWireId, false);
        plugin.forgetRemoteBodyTreatment(body);
        plugin.neutralizeRemoteBody(body, fresh);
        plugin.seatHud.forgetSeatName(body); // The vacated hull sheds the seat's tag + name
        return sourceCaptain;
    }

    /** capture-then-rewrite; NEVER the MMNT raced-hull removeEntity (M1). */
    private boolean applyRemoteCommit(ClientCommandTransfer e) {
        Long seatKey = Long.valueOf(e.seatWireId());
        Long toKey = Long.valueOf(e.toWireId());
        PredictReconcileCombatPlugin.RemoteEntity rowA = plugin.remoteEntities.get(seatKey);
        if (rowA == null) {
            anomaliesDropped++;
            PilotWatchLog.error("cc-transfer remote COMMIT: no seat row " + e.seatWireId()
                    + " (dropped)", null);
            return true;
        }
        // A station-module TARGET is MMNT's adopt — the own-side
        // rule (applyOwnCommit's rowless module-mounted arm) mirrored. KIND_MOUNT fans BEFORE
        // this COMMIT on the CTRL FIFO and mmnt.drain runs ahead of applyStaged, so the seat row
        // already drives the module body and the target id NEVER re-rows (it re-keys fresh at
        // dismount). Our job here is ONLY the source re-home: the LIVE hull the mount stashed
        // (body-continuous into the announced source row) — the module is never re-pointed
        // or retired by us. One watch's seat 1 showed the ERROR-drop noise, then the stolen
        // adopt (rowA → a disposed body, module row 59 retired, no mounted record) ⇒
        // dismount dropped ⇒ the seat's old hull drawn in the citadel slot.
        flyingPodSeats.remove(seatKey); // Any COMMIT ends the seat's flight
        ModuleMountLane.MountedSeat mounted = plugin.mountedSeats.get(seatKey);
        if (mounted != null && !mounted.own && mounted.retiredWireId == e.toWireId()) {
            moduleParkStart.remove(seatKey);
            ShipAPI vacated = mounted.takeVacatedHull();
            String sourceCaptain = reHomeRemoteSource(e, rowA, vacated, mounted.priorVariantId);
            commitsApplied++;
            PilotWatchLog.info("cc-transfer remote COMMIT: seat " + e.seatWireId() + " -> wire "
                    + e.toWireId() + " is a mounted station module (MMNT owns the adopt); source "
                    + (vacated != null ? "re-homed under " : "unbound at ") + e.fromWireId()
                    + " (applied=" + commitsApplied + ") sourceCaptain=" + sourceCaptain);
            return true;
        }
        PredictReconcileCombatPlugin.RemoteEntity rowB = plugin.remoteEntities.get(toKey);
        boolean moduleTarget = rowB != null ? rowB.stationModule
                : (!retiredWireIds.contains(toKey) && plugin.mmnt.mountedOrPending(e.seatWireId()));
        if (moduleTarget) {
            // The mount is still pending (cluster not committed / slot body not resolvable):
            // park bounded — NEVER re-point rowA + retire the module row (the stolen adopt).
            long now = System.currentTimeMillis();
            Long since = moduleParkStart.get(seatKey);
            if (since == null) {
                moduleParkStart.put(seatKey, Long.valueOf(now));
                return false;
            }
            if (now - since.longValue() <= MODULE_PARK_MILLIS) {
                return false;
            }
            moduleParkStart.remove(seatKey);
            PilotWatchLog.info("cc-transfer remote COMMIT: seat " + e.seatWireId() + " target "
                    + e.toWireId() + " is a station module whose mount is still pending after "
                    + MODULE_PARK_MILLIS + " ms - COMMIT retired unapplied (MMNT owns the adopt;"
                    + " source " + e.fromWireId() + " re-homes by announce)");
            return true;
        }
        if (rowB == null) {
            anomaliesDropped++;
            PilotWatchLog.error("cc-transfer remote COMMIT: no target row " + e.toWireId()
                    + (retiredWireIds.contains(toKey)
                            ? " (already retired — double delivery, H2)" : " (dropped)"), null);
            return true;
        }
        ShipAPI targetBody = rowB.ship;
        if (targetBody == null) {
            if (rowB.killed) {
                anomaliesDropped++;
                PilotWatchLog.error("cc-transfer remote COMMIT: target row " + e.toWireId()
                        + " is killed+unbound (dropped)", null);
                return true;
            }
            return false; // park-and-retry
        }
        // ---- capture ----
        ShipAPI sourceBody = rowA.ship; // may be null (seat replica unbound — fresh row unbound)
        String targetVariant = plugin.variantById.get(toKey);
        String seatVariant = plugin.variantById.get(seatKey);
        // ---- defuse + retire rowB ----
        rowB.capActive = false;
        rowB.ship = null;
        plugin.remoteEntities.remove(toKey);
        plugin.variantById.remove(toKey);
        retiredWireIds.add(toKey);
        // ---- re-point rowA -> the target body (MMNT remote fork, minus the raced removeEntity) ----
        rowA.ship = targetBody;
        // bound-then-died — a hulk with killed=false would zombie-drag to live pose
        // and block the respawn self-heal; killed=true routes respawnRemote on an alive pose
        // (killRemote no-ops on the already-dead body). Guard read = isAlive(), never isHulk().
        rowA.killed = !targetBody.isAlive();
        rowA.rekeyPoseHold = true;
        rowA.rekeyHoldSinceTick = Long.MIN_VALUE;
        if (targetVariant != null) {
            plugin.variantById.put(seatKey, targetVariant);
        }
        plugin.forgetRemoteBodyTreatment(targetBody);
        plugin.neutralizeRemoteBody(targetBody, rowA);
        String sourceCaptain = reHomeRemoteSource(e, rowA, sourceBody, seatVariant);
        commitsApplied++;
        // The server re-captains the target at this
        // commit (the captain follows the seat); the replica follows AFTER the re-home (the
        // source's captain revert included), so the commit is whole before the lever runs, and a
        // lever throw is caught into an err- token on the row below, never a consumed-with-error.
        // A dead target re-captains through its respawn's seat mint.
        String captain = "skip-dead";
        if (remoteCommitLeverFires(true, moduleTarget, false, false) && targetBody.isAlive()) {
            com.fs.starfarer.api.combat.ShipVariantAPI v = targetBody.getVariant();
            String fielded = v != null ? v.getHullVariantId() : targetVariant;
            try {
                captain = OfficerLoadout.applySeatStatsLever(targetBody, fielded,
                        MatchMissionBuilder.descriptorVariantForShipIdOrNull(e.seatWireId()),
                        MatchMissionBuilder.isSeatShipId(e.seatWireId()), "remote-transfer", true)
                        ? "lever" : "no-row";
            } catch (Throwable t) {
                // The commit is whole by here — a lever throw (the
                // 6-arg lever has no catch of its own) is a row token, never a consumed-with-error.
                captain = "err-" + t.getClass().getSimpleName();
            }
        }
        PilotWatchLog.info("cc-transfer remote COMMIT applied: seat " + e.seatWireId()
                + " -> wire " + e.toWireId() + " body; source re-homed under " + e.fromWireId()
                + (sourceBody == null ? " (unbound)" : "") + " (applied=" + commitsApplied + ")"
                + " captain=" + captain + " sourceCaptain=" + sourceCaptain);
        return true;
    }

    /**
     * Which remote-COMMIT arm re-captains the
     * replica — ONLY the applied hull arm, the one arm where the server's hull re-key ran and a
     * live target body is in hand. The module arm is the mount adopt's (the module body is not
     * this lane's), a dropped commit applied nothing, and a parked one has not applied yet
     * (its retry reaches the applied arm).
     */
    static boolean remoteCommitLeverFires(boolean appliedHullArm, boolean moduleArm,
            boolean dropped, boolean parked) {
        return appliedHullArm && !moduleArm && !dropped && !parked;
    }

    /**
     * The BEGIN captain revert, the server re-key verbs'
     * twin — the LIVE hull a seat leaves gets its OWN original captain back on the Ship (and on
     * its member when present), never vanilla's "blank it when the departing person was the
     * original" test, an object-identity read that splits across the ends. A null original is
     * passed through (the engine mints its default person), as the server does. Skips (the
     * server's kept-* set): a null body, a dead one (wreck), a pod (in flight, or its pilotable
     * twin by variant). Returns the row token; a throw is folded into an {@code err-} token on
     * the caller's row (the re-home must still complete).
     *
     * <p>{@code twinOrNull} (non-null) REPLACES
     * {@code getOriginalCaptain()} — the own lane's {@link OwnSeatLifecycleLane#adoptedOriginalOrNull}
     * for the claim's in-place-adopted body, whose engine original is the build-time person while
     * the server reverts that hull to the seat's twin; every caller passes it (null where the body
     * can never be the adopted one). The success token names the restored person's level,
     * {@code reverted:L<n>} (read AFTER the writes; a failed read = {@code reverted:L?}, the writes
     * stand), or {@code reverted:null} for the engine-default pass-through: engine-free ASCII,
     * printed raw on the BEGIN / COMMIT / DISMOUNT rows.
     */
    static String revertVacatedCaptain(ShipAPI body,
            com.fs.starfarer.api.characters.PersonAPI twinOrNull) {
        if (body == null) {
            return "skip-null";
        }
        com.fs.starfarer.api.characters.PersonAPI original;
        try {
            if (!body.isAlive()) {
                return "skip-dead";
            }
            com.fs.starfarer.api.combat.ShipVariantAPI v = body.getVariant();
            if (body.isShuttlePod() || (v != null && isPodReplicaVariant(v.getHullVariantId()))) {
                return "skip-pod";
            }
            original = twinOrNull != null ? twinOrNull : body.getOriginalCaptain();
            body.setCaptain(original);
            com.fs.starfarer.api.fleet.FleetMemberAPI member = body.getFleetMember();
            if (member != null) {
                member.setCaptain(original);
            }
        } catch (Throwable t) {
            return "err-" + t.getClass().getSimpleName();
        }
        if (original == null) {
            return "reverted:null";
        }
        try {
            return "reverted:L" + original.getStats().getLevel();
        } catch (Throwable t) {
            return "reverted:L?"; // the token is the count: the row prints it
        }
    }

    /** The pod replica's pilotable variant (it reads {@code isShuttlePod()} false) — never a
     *  captain-bearing hull for the BEGIN revert. */
    static boolean isPodReplicaVariant(String hullVariantId) {
        return ClientCommandTransfer.POD_WIRE_VARIANT_ID.equals(hullVariantId);
    }

    /**
     * The re-homed SOURCE body -> the remote row keyed {@code fromWireId} (the applyRemoteCommit
     * tail, carved so the module-target arm shares it: there {@code sourceBody} is
     * the MMNT-stashed vacated hull and {@code seatVariant} the record's pre-mount variant —
     * the seat row itself already reads the MODULE variant by the time the COMMIT lands).
     * Returns the BEGIN captain revert's token ({@link #revertVacatedCaptain}, run first): at the
     * REMOTE BEGIN the body is the hull the seat leaves; at a COMMIT it is the pod replica (skip).
     */
    private String reHomeRemoteSource(ClientCommandTransfer e,
            PredictReconcileCombatPlugin.RemoteEntity rowA, ShipAPI sourceBody,
            String seatVariant) {
        // After an own BEGIN the adopted body lives
        // on as F's replica, so a teammate's later departure from it reverts through HERE.
        String sourceCaptain = revertVacatedCaptain(sourceBody,
                plugin.ownSeat.adoptedOriginalOrNull(sourceBody));
        Long fromKey = Long.valueOf(e.fromWireId());
        // ---- the re-homed source body -> FRESH remote row keyed fromWireId ----
        // W3-I(b): the server precedes this frame with a RAPP row carrying the
        // re-homed source's TRUE identity (the capture above reads the seat row AFTER the
        // RAPP seat-repoint overwrote it — one recorded run registered the retired aurora as a
        // MERLON under 54, and seat1 later FIELDED that merlon). A row already present for
        // fromWireId therefore OWNS the re-home. Same-variant transfers keep body continuity
        // (the variant-known fold never dropped the body — it binds straight into the
        // announced row); a shape-mismatched capture is the post-repoint re-mint, dropped
        // like the RosterAppendLane reorder race — spawn-on-sight mints the true shape at
        // the announced row's first pose.
        if (e.fromWireId() > 0) {
            PredictReconcileCombatPlugin.RemoteEntity announced =
                    plugin.remoteEntities.get(fromKey);
            if (announced != null) {
                String announcedVariant = plugin.variantById.get(fromKey);
                if (announced.ship == null && sourceBody != null && seatVariant != null
                        && seatVariant.equals(announcedVariant)) {
                    announced.ship = sourceBody;
                    plugin.remoteUnbound = Math.max(0, plugin.remoteUnbound - 1);
                    announced.rekeyPoseHold = true;
                    announced.rekeyHoldSinceTick = Long.MIN_VALUE;
                    plugin.forgetRemoteBodyTreatment(sourceBody);
                    plugin.neutralizeRemoteBody(sourceBody, announced);
                    PilotWatchLog.info("cc-transfer remote COMMIT: source bound into announced"
                            + " row " + e.fromWireId() + " (variant-continuous)");
                } else {
                    if (sourceBody != null) {
                        if (sourceBody.isAlive() && plugin.engine != null) {
                            // post-repoint re-mint — no wire feed will ever drive it again
                            plugin.removeStaleBody(sourceBody, "transfer-source-remint"); //
                        }
                        plugin.forgetRemoteBodyTreatment(sourceBody);
                    }
                    PilotWatchLog.info("cc-transfer remote COMMIT: re-home deferred to"
                            + " announced row " + e.fromWireId()
                            + " (variant=" + FallbackAnnounce.sanitize(announcedVariant, 64)
                            + (sourceBody != null ? "; stray capture dropped" : "; unbound")
                            + ")");
                }
            } else {
                PredictReconcileCombatPlugin.RemoteEntity fresh =
                        new PredictReconcileCombatPlugin.RemoteEntity(e.fromWireId(), rowA.side);
                fresh.rekeyPoseHold = true;
                fresh.rekeyHoldSinceTick = Long.MIN_VALUE;
                if (sourceBody != null) {
                    fresh.ship = sourceBody;
                    plugin.forgetRemoteBodyTreatment(sourceBody);
                    plugin.neutralizeRemoteBody(sourceBody, fresh);
                } else {
                    plugin.remoteUnbound++; // unbound — spawn-on-sight mints at first pose
                    plugin.remoteBindCompleteLogged = false;
                }
                plugin.remoteEntities.put(fromKey, fresh);
                if (seatVariant != null) {
                    plugin.variantById.put(fromKey, seatVariant);
                }
            }
        }
        return sourceCaptain;
    }

    /**
     * REMOTE BEGIN (staged, post-RAPP): the seat's vacated body re-homes under F
     * ({@link #reHomeRemoteSource} — F's announced row wins; a WRECK binds too), then the seat
     * row unbinds so spawn-on-sight mints the flying cc_pod replica at S's next pose,
     * {@code CollisionClass.NONE} while {@link #isFlyingPodSeat}. A RESUME ({@code from == S})
     * keeps the stranded cc_pod replica and flips it NONE in place (same sprite, no re-mint).
     */
    private boolean applyRemoteBegin(ClientCommandTransfer e) {
        Long seatKey = Long.valueOf(e.seatWireId());
        String priorVariant = stagedBeginVariant.remove(seatKey);
        if (flyingPodSeats.contains(seatKey)) {
            // The server re-fans BEGIN on every in-flight target change (the
            // retarget re-fan + RETARGETED_RETURNING in `CommandTransferServeLane`)
            // — same pod, same source; a second vacate would bind the FLYING replica into F's
            // row and re-mint it. Target-only, like beginOwn's ownTransit branch.
            PilotWatchLog.info("cc-transfer remote RETARGET BEGIN: seat " + e.seatWireId()
                    + " -> " + e.toWireId() + " (pod already flying; no re-home)");
            return true;
        }
        PredictReconcileCombatPlugin.RemoteEntity rowA = plugin.remoteEntities.get(seatKey);
        if (rowA == null) {
            anomaliesDropped++;
            PilotWatchLog.error("cc-transfer remote BEGIN: no seat row " + e.seatWireId()
                    + " (dropped)", null);
            return true;
        }
        flyingPodSeats.add(seatKey);
        ShipAPI sourceBody = rowA.ship;
        boolean resume = e.fromWireId() == e.seatWireId();
        String sourceCaptain = "skip-resume";
        if (resume) {
            if (sourceBody != null) {
                try {
                    sourceBody.setCollisionClass(
                            com.fs.starfarer.api.combat.CollisionClass.NONE);
                } catch (Throwable ignore) {
                    // presentation; the server body is NONE regardless
                }
            }
        } else {
            sourceCaptain = reHomeRemoteSource(e, rowA, sourceBody, priorVariant);
            rowA.ship = null; // the seat replica re-mints as the flying pod (cc_pod via RAPP)
            rowA.killed = false;
            rowA.rekeyPoseHold = true;
            rowA.rekeyHoldSinceTick = Long.MIN_VALUE;
            plugin.remoteUnbound++;
            plugin.remoteBindCompleteLogged = false;
        }
        PilotWatchLog.info("cc-transfer remote BEGIN (one-entity): seat " + e.seatWireId()
                + " -> " + e.toWireId() + (resume ? " RESUME (replica flipped NONE in place)"
                        : " source " + (sourceBody != null ? "re-homed under " : "unbound at ")
                                + e.fromWireId() + "; pod replica re-mints NONE")
                + " sourceCaptain=" + sourceCaptain);
        return true;
    }

    /** REMOTE ABORT: RETURNING ({@code from == F}) needs nothing (the dock-back
     *  COMMIT rides applyRemoteCommit with rowB = F's row); STRANDED ({@code from == S}) ends
     *  the flight — the replica flips SHIP (the server body is a normal frigate now). */
    private void abortRemote(ClientCommandTransfer e) {
        if (e.fromWireId() != e.seatWireId()) {
            return; // RETURNING
        }
        Long seatKey = Long.valueOf(e.seatWireId());
        flyingPodSeats.remove(seatKey);
        PredictReconcileCombatPlugin.RemoteEntity rowA = plugin.remoteEntities.get(seatKey);
        ShipAPI pod = rowA != null ? rowA.ship : null;
        if (pod != null) {
            try {
                pod.setCollisionClass(com.fs.starfarer.api.combat.CollisionClass.SHIP);
            } catch (Throwable ignore) {
                // presentation; hull/armor reconcile hold it at wire truth regardless
            }
        }
        PilotWatchLog.info("cc-transfer remote ABORT STRANDED: seat " + e.seatWireId()
                + " reason=" + e.reason() + " -> pod replica "
                + (pod != null ? "flipped SHIP" : "unbound (mints SHIP on sight)"));
    }

    private void armHomeFlightWatch(String why) {
        if (!homeFlightWatch) {
            homeFlightWatch = true;
            homeFlightRepointLogged = false;
            PilotWatchLog.info("cc-transfer home-flight watch ARMED (" + why + "): the local"
                    + " pod keeps flying — its TO is held on the live own body until IDLE");
        }
    }

    /**
     * RC2 pump (runs at !ownTransit while armed): IN_FLIGHT ⇒ hold the pod's TO on
     * the in-play LIVE own body ({@code plugin.ownShip} — a respawn under the same wire id
     * re-points it, so the pod turns to the fresh hull; vanilla's guard refuses
     * a hulk TO, so a dead own hull is not attempted and the landing-on-the-wrong-body case
     * falls to the IDLE guard below = the vanilla "dead pilot sits on the wreck" outcome);
     * LANDING ⇒ wait (a retarget at LANDING strands); IDLE ⇒ the flight is over —
     * concealed-swap guard once + disarm; UNKNOWN ⇒ adapter dead, disarm.
     */
    private void maintainHomeFlightWatch() {
        int state = adapter.state(plugin.sideAuthority());
        if (strandHiddenPod != null) {
            if (state == TransferManagerAdapter.STATE_IDLE
                    || state == TransferManagerAdapter.STATE_UNKNOWN) {
                strandHiddenPod = null; // B removed the pod at its landing (or is unreadable)
            } else {
                hideStrandedPod(strandHiddenPod); // Invisible until IDLE
            }
        }
        if (state == TransferManagerAdapter.STATE_LANDING) {
            return;
        }
        if (state == TransferManagerAdapter.STATE_IN_FLIGHT) {
            ShipAPI own = plugin.ownShip;
            if (own != null && plugin.engine != null && own.isAlive()
                    && plugin.engine.isEntityInPlay(own)
                    && adapter.transferTarget(plugin.sideAuthority()) != own) {
                boolean repointed = adapter.retargetTo(plugin.sideAuthority(), own);
                if (!homeFlightRepointLogged) {
                    homeFlightRepointLogged = true;
                    PilotWatchLog.info("cc-transfer home-flight watch: post-abort pod TO is not"
                            + " the own body -> re-pointed onto it (repointed=" + repointed
                            + "); the vanilla landing now swaps onto the RIGHT hull");
                }
            }
            return;
        }
        homeFlightWatch = false;
        if (state == TransferManagerAdapter.STATE_IDLE) {
            maintainConcealedSwapGuard("post-abort home flight landed");
        }
    }

    /**
     * FLOOR — the idle player-pointer divergence guard:
     * with every designed lane quiet and the vanilla machine IDLE, the engine's player ship
     * must be the seat's own body. The flight's "seated in the citadel" was a LOCAL
     * vanilla landing the server never committed (no {@code transfer_commit} row in the
     * whole serve log); the concealed-swap guard only ran on a few triggers (95 s late), so
     * this samples every frame. Repair = the validated in-play restore (never a dead hull);
     * loud once per divergence, quiet retries while one persists.
     */
    private void maintainIdleDivergenceGuard() {
        // awaitingBegin = NOT a quiet lane (the stuck-watchdog predicate): a clicked transfer
        // to a NEAR target vanilla-lands under the 4 s ack window — that IDLE
        // divergence belongs to the BEGIN-ack timeout branch, never a 2-frame yank.
        //
        if (ownTransit || awaitingBegin || pendingCollapse || homeFlightWatch
                || plugin.engine == null || plugin.ownShip == null || !adapter.usable()) {
            idleDivergenceFrames = 0;
            return;
        }
        ShipAPI player = plugin.engine.getPlayerShip();
        if (player == null || player == plugin.ownShip
                || !plugin.engine.isEntityInPlay(plugin.ownShip)
                || adapter.state(plugin.sideAuthority()) != TransferManagerAdapter.STATE_IDLE) {
            idleDivergenceFrames = 0;
            idleDivergenceLogged = false;
            return;
        }
        if (++idleDivergenceFrames < IDLE_DIVERGENCE_DWELL_FRAMES) {
            return;
        }
        idleDivergenceFrames = 0;
        if (!idleDivergenceLogged) {
            idleDivergenceLogged = true;
            maintainConcealedSwapGuard("idle divergence");
        } else {
            forcePlayerTo(inPlayRestoreTarget()); // quiet retry of a persisting divergence
        }
    }

    private void forcePlayerTo(ShipAPI body) {
        if (body != null && plugin.engine != null) {
            try {
                plugin.engine.setPlayerShipExternal(body); // engine-guarded no-op when unchanged; idempotent
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * 0335-F1: the ONE validated restore-target pick — the live own pointer if in-play (a
     * partial-adopt throw may already have flipped it — seat truth), else the transit source
     * hull if in-play, else {@code null} (the caller leaves the player where they stand with a
     * loud log). EVERY forced player restore routes through this: a raw
     * {@code forcePlayerTo(ownShip)} can land the player on a REMOVED entity =
     * uncontrollable — the very state the restore exists to repair (@0334 lesson, recurred as
     * 0335-F1 in the two W3-FIX guard paths). The test is in-play, NOT alive: a dead-but-present
     * own (the wreck the player spectates from) is a valid restore — vanilla keeps the player
     * pointer on the hulk too; a dead-seat restore honestly returns the wreck.
     */
    private ShipAPI inPlayRestoreTarget() {
        if (plugin.engine == null) {
            return null;
        }
        if (plugin.ownShip != null && plugin.engine.isEntityInPlay(plugin.ownShip)) {
            return plugin.ownShip;
        }
        if (sourceHull != null && plugin.engine.isEntityInPlay(sourceHull)) {
            return sourceHull;
        }
        return null;
    }

    /**
     * The ONE transit-ending restore (@0334 /): the player force now lives INSIDE, so
     * EVERY exit — ABORT, observer, and all three own-COMMIT anomaly exits — ends with the
     * player off the intangible pod (the source's per-frame decision hold lapses with
     * {@code ownTransit} — no lock to clear). Fail-safe restore target: the live own pointer if in-play (a
     * partial-adopt throw may already have flipped it — seat truth), else the transit source
     * hull, else leave-on-pod with a loud log. The successful own COMMIT does NOT route here —
     * its adopt already owns the player pointer ({@code clearTransit} directly). H2-replay
     * exits carry {@code sourceHull == null} and an in-play own hull, so the force no-ops
     * (the engine's same-target guard).
     */
    private void endTransitRestore(String why) {
        ShipAPI restore = inPlayRestoreTarget();
        if (restore != null) {
            forcePlayerTo(restore); // idempotent under the vanilla ABORT swap-back path
        } else if (sourceHull != null || plugin.ownShip != null) {
            PilotWatchLog.error("cc-transfer transit end (" + why + "): no in-play restore"
                    + " target — player left where they stand", null);
        }
        plugin.ownLife.rearmOwnPoseBaselines(); // the restored hull must not glide-snap
        clearTransit(why);
    }

    private void clearTransit(String why) {
        // F1: the episode stops naming its ids here — disarm their adopt-hold stamps (a row
        // the adopt already retired is a no-op miss; capture before the fields zero below).
        clearAdoptHold(grantedTargetWireId);
        if (pendingOwnCommit != null) {
            clearAdoptHold(pendingOwnCommit.toWireId());
        }
        // S is the own seat id — NEVER retired (the old pod-id retire is gone).
        ownTransit = false;
        observerMode = false;
        observerMintPending = false;
        observerBody = null;
        localPod = null;
        sourceHull = null;
        podWireId = 0;
        beginFromWireId = 0;
        sourceVariant = null;
        sourceReHomed = false;
        sourceIsClaimPlaceholder = false;
        returning = false;
        strandPending = false;
        rowlessParkStartMillis = 0L;
        podSearchFrames = 0;
        pendingOwnCommit = null;
        grantedTargetWireId = 0;
        retargetAckPending = false;
        synthDriven = false;
        // pendingCollapse deliberately SURVIVES transit end: an ABORT-at-LANDING
        // restore can be undone by the vanilla swap firing AFTER endTransitRestore — the
        // armed latch catches that at IDLE. resetRound is its only unconditional clear.
        // W3-E meter: capture BEFORE the chokepoint clears below so the terminal
        // clear is not part of the emitted transit (miss/clear/snap attribute the FLIGHT).
        String pinMeter = " frames=" + pinDeltaCount + " pinFrames=" + podPinFrames
                + " meanDelta=" + (pinDeltaCount > 0
                        ? String.format("%.1f", pinDeltaSum / pinDeltaCount) : "0")
                + " maxDelta=" + String.format("%.1f", pinDeltaMax)
                + " samples=" + podSlot.meterSamples() + " miss=" + podSlot.meterMisses()
                + " clear=" + podSlot.meterClears() + " snap=" + podSlot.meterSnaps()
                + " tclamp=" + podSlot.meterTClamps();
        podSlot.clear(); // R8 SNAP edge: COMMIT/ABORT clear the pin slot at this chokepoint
        PilotWatchLog.info("cc-transfer transit END (" + why + ")");
        // ONE write-only line per transit (instrument-then-fix law — the M1-vs-M2 verdict
        // picks the W3-E fix at the next arm); zero cost outside ownTransit (pinPump early
        // return), no new knob (transit itself rides transfer.enabled).
        PilotWatchLog.info("cc-transfer pin-delta [" + why + "]:" + pinMeter);
        pinDeltaCount = 0;
        pinDeltaSum = 0.0;
        pinDeltaMax = 0f;
        podPinFrames = 0;
        podSlot.resetMeter();
    }

    /** W3-E meter — per-transit |post-pin − pre-pin| pod displacement accumulate
     *  (M1 route-divergence amplitude); reset with the DLS meters at the transit-end emit. */
    private int pinDeltaCount;
    private double pinDeltaSum;
    private float pinDeltaMax;

    /**
     * The per-TRANSIT-frame pose pin — the FINAL statement of {@code advance()} (the
     * presenter-pins-last idiom; the engine already ran the transfer manager, the pod's kept AI
     * and the pod physics, so this hard-set is the frame's last word). 4-field DemDrone idiom
     * ({@link DemDronePresenter#presentPass}) from the RAW wire pose (pin-source pick recorded in the class doc).
     */
    void pinPump(ServerSnapshotFrame snapshot) {
        if (!ownTransit || snapshot == null) {
            return;
        }
        if (strandPending) {
            runStrandExit(snapshot); // Needs the S pose; ends transit on success
            return;
        }
        if (observerMintPending) {
            maintainObserverMint(snapshot);
        }
        long nowNanos = System.nanoTime();
        long simTick = snapshot.simTick();
        boolean fresh = simTick != lastFedSimTick; // R8: feed once per NEW sim tick
        if (fresh) {
            lastFedSimTick = simTick;
        }
        // S IS the pod: the pinned body presents the own seat row — B's local pod,
        // or the observer-mode cc_pod own body. The delayed-lerp pin stays the frame's last
        // word over the kept TransferCommandAI.
        ShipAPI pinned = localPod != null ? localPod : (observerMode ? observerBody : null);
        if (pinned != null && pinned.isAlive() && podWireId > 0) {
            if (fresh) {
                ServerEntityPose pod = snapshot.entityById(podWireId);
                if (pod != null) {
                    podSlot.feed(podWireId, pod, simTick, nowNanos);
                } else {
                    podSlot.noteMiss();
                }
            }
            // W3-E meter: pre-pin pose = the kept-AI/physics route's frame result;
            // the post-pin distance to it IS the per-frame route-divergence yank (M1). The pin
            // mutates the body's own Vector2f in place, so capture floats first.
            float preX = 0f, preY = 0f;
            boolean preRead = false;
            try {
                org.lwjgl.util.vector.Vector2f preLoc = pinned.getLocation();
                if (preLoc != null) {
                    preX = preLoc.x;
                    preY = preLoc.y;
                    preRead = true;
                }
            } catch (Throwable ignore) {
                // presentation meter; never break the frame
            }
            if (podSlot.pin(pinned, nowNanos)) {
                podPinFrames++;
                if (preRead) {
                    try {
                        org.lwjgl.util.vector.Vector2f postLoc = pinned.getLocation();
                        if (postLoc != null) {
                            float dx = postLoc.x - preX;
                            float dy = postLoc.y - preY;
                            float d = (float) Math.sqrt(dx * dx + dy * dy);
                            pinDeltaCount++;
                            pinDeltaSum += d;
                            if (d > pinDeltaMax) {
                                pinDeltaMax = d;
                            }
                        }
                    } catch (Throwable ignore) {
                        // presentation meter; never break the frame
                    }
                }
            }
            // slot empty (wire pod absent > miss budget): pin off — the DENY/ABORT
            // retarget-back owns the fallback; the kept vanilla AI flies the pod
        }
        // The own-source pin + the per-frame decision hold are GONE: the
        // vacated source is F's neutralized remote from BEGIN (InertShipAI, wire-driven).
    }

    /**
     * Observer mode (late-join only): mint the cc_pod own body from the S row at rest
     * ({@link OwnSeatLifecycleLane#respawnOwnAtRest} — the S row's variant is already
     * cc_pod via the RAPP repoint); it is the pinned body until COMMIT (adopt) or strand
     * (becomes the own hull outright). Retries while the S pose is absent.
     */
    private void maintainObserverMint(ServerSnapshotFrame snapshot) {
        ServerEntityPose pose = snapshot.entityById(plugin.session.shipId());
        if (pose == null || pose.hitpoints() <= 0f) {
            return;
        }
        ShipAPI before = plugin.ownShip;
        plugin.ownSeat.respawnOwnAtRest(pose);
        if (plugin.ownShip != null && plugin.ownShip != before) {
            observerMintPending = false;
            observerBody = plugin.ownShip;
            PilotWatchLog.info("cc-transfer observer: cc_pod own body minted AT REST from the S"
                    + " row (" + pose.locX() + "," + pose.locY() + ") — pinned until COMMIT");
        }
    }

    /**
     * Rider {@code 1303-parkreset}: a seat body leaving the field drops its
     * pending-mount park stamp — a stamp orphaned by a mid-park seat death made that seat's
     * NEXT pending-mount COMMIT (after respawn) retire unapplied on its first retry (the
     * {@link #MODULE_PARK_MILLIS} window had long elapsed). Called from
     * {@link FoeStateLaneCoordinator#killRemote} for EVERY departing remote id — a non-seat id
     * is a free map miss, so no seat test is needed here.
     */
    void forgetSeatPark(long seatWireId) {
        moduleParkStart.remove(Long.valueOf(seatWireId));
    }

    /** Round boundary ({@code finishMatchReset} relay): every per-round latch/queue/set dies. */
    void resetRound() {
        for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
            re.adoptHoldSinceTick = Long.MIN_VALUE; // F1 belt: no stamp crosses a round wrap
        }
        staged.clear();
        moduleParkStart.clear();       // No park stamp crosses a round wrap
        retiredWireIds.clear();
        flyingPodSeats.clear();        // No flying-pod mark crosses a round wrap
        stagedBeginVariant.clear();
        pendingOwnCommit = null;
        returning = false;
        strandPending = false;
        strandHiddenPod = null;
        observerMintPending = false;
        observerBody = null;
        beginFromWireId = 0;
        sourceVariant = null;
        sourceReHomed = false;
        sourceIsClaimPlaceholder = false;
        awaitingBegin = false;
        triggerSentThisEpisode = false;
        departureTwinWrittenThisEpisode = false;
        ownTransit = false;
        observerMode = false;
        localPod = null;
        sourceHull = null;
        podWireId = 0;
        rowlessParkStartMillis = 0L;
        podSearchFrames = 0;
        lastSentTargetWireId = 0;
        grantedTargetWireId = 0;
        retargetAckPending = false;
        idleObservedThisRound = false; // round-scoped; brickLogged stays run-scoped
        pendingCollapse = false;       // The latch's one unconditional clear
        homeFlightWatch = false;       // Same law as pendingCollapse
        homeFlightRepointLogged = false;
        idleDivergenceFrames = 0;
        idleDivergenceLogged = false;
        stuckSinceMillis = 0L;         // stuckRestoreLogged stays run-scoped (brickLogged mirror)
        synthDriven = false;
        podSlot.clear();               // R8: round wrap clears the pin slot
        lastFedSimTick = Long.MIN_VALUE;
    }
}
