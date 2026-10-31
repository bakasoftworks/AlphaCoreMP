package cc.starsector.client.net;

import cc.starsector.client.FallbackAnnounce;
import cc.starsector.client.voice.VoiceClient;
import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.DatagramReliability;
import cc.starsector.server.runtime.Handshake13;
import cc.starsector.server.runtime.Handshake13Auth;
import cc.starsector.server.runtime.Handshake13Flight;
import cc.starsector.server.runtime.HandshakeCodec;
import cc.starsector.server.runtime.PilotIntentFrame;
import cc.starsector.server.runtime.PilotIntentWireCodec;
import cc.starsector.server.runtime.SnapDeflateCodec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The mod-side UDP sibling of {@link PredictReconcileClientSession}:
 * the {@link PredictSession} the predict combat plugin
 * drives when {@code cc.predict.transport=udp}. Session-shaped where the game-free
 * {@code cc.starsector.server.client.PilotIntentDatagramClient} is clock-shaped, but mirroring its
 * PROVEN protocol behaviors: HELLO/WELCOME
 * admission through the server's PUBLIC nested records (no parallel codec), UP intents on the
 * latest-wins SNAP channel, PDCL/SNAM on reliable CTRL, DOWN SNAP behind the round barrier,
 * DOWN CTRL in-order (GPEE/EDSP/MRST/SNMS), BYE-ACK = BYE echo, and the goodbye law (bounded
 * CTRL flush on BOTH goodbye paths — a datagram session must app-flush what TCP's kernel flushes
 * before FIN, or lost-in-flight declarations die with the process).
 *
 * <p><b>Threading (mirrors the transport design):</b> a reader daemon feeds the {@link DatagramReliability}
 * core and a timer daemon pumps retransmits/keepalives; the plugin's net threads only ever touch
 * this class's public surface. {@link #receiveSnapshot} drains the in-order CTRL queue to its
 * consumers, then blocking-takes a FRESH snapshot from a one-slot latest-wins buffer — conflation
 * under load is the datagram contract, exactly-once is CTRL's. Lock order: core callbacks may take
 * {@code lock}; nothing holding {@code lock} calls into the core.
 *
 * <p><b>Round barrier — flip at DRAIN, not delivery (deliberate deviation from the game-free
 * client):</b> the game-free client flips {@code currentRoundId} the instant the reader delivers
 * an MRST. Here the flip happens when the CONSUMER drains that MRST, because the TCP contract this
 * class must honor promises the reset callback in-line immediately BEFORE the next-round snapshot
 * {@code receiveSnapshot} returns — a delivery-time flip could publish the fresh round's snapshot
 * to a blocked take before the plugin ever saw the reset, breaking its tear-down-and-rebuild
 * ordering. Until the drain flips, next-round SNAPs park in the one-slot future buffer and are
 * promoted on the flip (latest-wins); other rounds drop counted.
 *
 * <p><b>Script-sandbox discipline (B3 step 0):</b> loaded in-game by classloader
 * {@code com.fs.starfarer.loading.scripts.B}, which denies ALL {@code java.io} beyond a small
 * allowlist ({@code ByteArrayInputStream}/{@code IOException} used here are ON it) while
 * {@code java.net} is unrestricted [observed]. CTRL/SNAP payloads decode through
 * {@link MultiplexedServerStreamReader} (each datagram payload is exactly one multiplexed frame —
 * the proven readers, zero new decode logic); intents/declarations/seat-names encode through the
 * existing allowlist-clean writers. Every re-HELLO nonce draws from the dial's ONE
 * {@link SecureRandom} (a final here; constructed and first drawn on the dialing thread, so a
 * draw under {@code lock} is a warm call — the in-game {@code DoorProbe} uses the same class);
 * a version-12 nonce is an idempotency/correlation token, a version-13 nonce binds one sealed
 * envelope.
 *
 * <p>Fail-closed: death detection keeps the wire vocabulary ({@code udp_silence} on
 * {@value #READ_DEADLINE_MILLIS} ms of inbound silence — the TCP 60 s read-timeout mirror;
 * {@code ctrl_window_stall} past one RTO of window-full). The plain no-demux
 * {@link #receiveSnapshot()} throws on ANY CTRL delivery — its TCP twin reads a bare AWSS stream
 * and would fail-close on any interleaved frame; a knob-off UDP lane is not a supported
 * composition ({@code udp} requires {@code cc.predict.duplex=true}).
 *
 * <p><b>SNAP freshness levers (ratified
 * default-ON):</b> with {@code cc.predict.snapTickConflate} ON the two round-barrier SNAP slots
 * become per-TICK groups — only the newest server tick's chunks survive (kills the one-slot
 * co-tick overwrite, the split-tick slot term), and ALL of that tick's chunks drain FIFO one per
 * {@link #receiveSnapshot} call for the plugin's merger to fold. Tick grouping needs the tick at
 * DELIVERY, so the CAWZ unwrap moves from the drain to the reader thread and the groups store RAW
 * payloads ({@code decodePayload}'s wrapper check passes them through untouched). With
 * {@code cc.predict.snapStaleFold} additionally ON, the reliability core's seq-stale SNAP drops
 * are re-routed up a stale hook and folded by CONTENT tick — a reordered same-tick sibling chunk
 * is still-newest truth the seq gate alone cannot see (kills the gate term). Composition law:
 * staleFold REQUIRES tickConflate (a stale payload must never enter a one-slot buffer — an older
 * overwrite would REGRESS the slot); {@code snapTickConflate=false} reverts BOTH levers to the
 * byte-identical pre-lever paths (single slot, unwrap at drain, tick-blind core). Both knobs are
 * kill-switches, default ON, resolved once at construction; tickrate-agnostic by construction
 * (tick-granularity grouping + seq-gate folds — zero time constants).
 *
 * <p><b>Stale PASSTHROUGH (third lever, default-ON):</b> chunks the stale-fold
 * group REFUSES as older-tick ({@code t < slotTick}) are still per-TIER-fresh content the
 * merger's {@code tick >=} guards fold safely (the unconditional ordering guard) — a late
 * ship(T) chunk stays newest for its tier even after T+1's trailer arrived (trailers carry no
 * ship tier). With {@code cc.predict.snapStalePassthrough} ON they queue on a SEPARATE bounded
 * side-deque (out-of-group by construction — survives group adoption/eviction; cap
 * {@value SnapFreshnessBuffer#STALE_PASSTHROUGH_CAP}, overflow drops OLDEST counted) and drain AFTER the fresh
 * group (fresh-first: passthrough never delays newest-tick delivery — the plugin's net loop is
 * a continuous blocking exchange, so the deferral costs µs). Composition law: passthrough
 * requires staleFold (transitively tickConflate); either parent OFF reverts this rung to the
 * byte-identical stale-fold refusal drop.
 *
 * <p><b>the client re-HELLO loop:</b>
 * the session-death lanes above (silence, CTRL stall, plus a reader receive failure on a live
 * socket — Windows surfaces a dead server's ICMP port-unreachable as a connection-reset receive)
 * ARM a standing re-admit instead of killing the session: HELLO with the connect credentials on
 * the SAME app-scoped socket (the design freeze's socket-survives contract), every
 * {@value #HELLO_RETRY_MILLIS} ms backing off ×2 to the {@value #RE_HELLO_INTERVAL_CAP_MILLIS} ms
 * cap, INDEFINITE until an admitted WELCOME (rebind session/round, swap in a fresh reliability
 * core, drop the dead session's drained state — the D4.5 late-join adopt path rebuilds it) or a
 * match-end BYE. While armed ({@link #reconnecting()}) receives block and sends drop counted —
 * the plugin free-runs on prediction and presents "reconnecting". A REJECTED WELCOME stays
 * TERMINAL (the door spoke and refused the credential — matches {@code connect}); {@code close()}
 * stays the caller's exit. One nonce per EPISODE, so a retransmit crossing its WELCOME resolves
 * as the server's idempotent resend, never a takeover of the fresh session.
 *
 * <p><b>kicked-terminal:</b> a drained HOUS KICKED notice sets
 * {@code kickedTerminal}, which refuses every FALLBACK arm and cancels an in-flight episode (a
 * kicked client never auto-re-HELLOs — pre-D4b the 60 s silence path silently re-admitted the
 * victim) and drops intent/CTRL/voice sends, but deliberately stays OUT of {@link #isDefunct}: the app-scoped session
 * stays dialable, and the deliberate mission re-entry {@link #armReAdmission} clears the latch
 * pair and rejoins through the normal fresh-nonce TAKEOVER lane. Kick semantics = removed from
 * the ROUND, deliberate rejoin allowed.
 */
public final class PredictReconcileDatagramSession implements PredictSession {

    /**
     * A door {@code WELCOME status=REJECTED} as a TYPED failure
     * — the decoded machine reason rides as data, not only inside the message text, so the
     * connect retry loop can hand it to the loud-fail banner (it used to collapse every
     * refusal into the generic CONNECT FAILED line). {@code reason} is wire text from the door:
     * consumers scrub it at their sink ({@code FallbackAnnounce.sanitize}) before display.
     */
    public static final class AdmissionRejected extends IOException {
        private static final long serialVersionUID = 1L;
        public final String reason;

        AdmissionRejected(String phase, String reason) {
            super("predict datagram " + phase + " rejected: " + reason);
            this.reason = reason;
        }
    }

    static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    /** HELLO retransmit cadence until a nonce-matching WELCOME. */
    static final int HELLO_RETRY_MILLIS = 250;
    /** B4 re-HELLO backoff ceiling (250 ms backing off to a 1 s cap, per the design freeze). Package, with the two
     * escalation constants below: the selftest pump rows size their waits from THESE, never a hand
     * mirror. */
    static final long RE_HELLO_INTERVAL_CAP_MILLIS = 1_000L;
    /** W5-D5: same-nonce re-HELLOs a nonce generation may send un-adopted
     * before the loop ESCALATES to a fresh nonce — the server then resolves the next HELLO as a
     * same-token TAKEOVER, tearing down the deaf session and minting a fresh one (the healing
     * lever observed live: fully healthy 89 ms after exactly that). First escalation
     * lands ~2.75 s into an episode at the default cadence; the threshold doubles per escalation
     * to {@value #RE_HELLO_ESCALATE_SENDS_CAP} so a persistent non-adopt backs off to ~1
     * takeover per 16 s instead of churning them (each is a seat displace + catch-up burst
     * server-side). Deliberately NON-terminating either way (the loop's INDEFINITE law): against
     * a down server the escalations hit nothing; against a deaf-but-alive feed path they churn
     * the seat at that bounded rate forever — accepted, because stopping is exactly the D5 bug.
     * Coarse BY DESIGN — a per-tick fresh nonce livelocks: the client outruns its own WELCOME
     * echo and the nonce gate below drops every answer. */
    static final long RE_HELLO_ESCALATE_AFTER_SENDS = 4L;
    static final long RE_HELLO_ESCALATE_SENDS_CAP = 16L;
    /** fresh-nonce escalations per episode between socket
     * RECREATES. The rejoin wedge was ~8 min of takeover flap on ONE source
     * port with zero welcomes adopted — consistent with a hairpin/NAT return path dead for that
     * mapping while client→server stayed alive. A new socket = a new source port = a fresh
     * mapping both ways, the survival move a public-internet client needs regardless of which
     * path the instrumentation pins. 3 escalations ≈ 27 s at the doubling cadence — past
     * every RTT-scale race, short enough that a seat displaced by a real path death re-admits
     * before the server's grace vacates it. */
    private static final long RE_HELLO_SOCKET_RECREATE_EVERY_ESCALATIONS = 3L;
    /** Inbound-silence death bound — the TCP session's 60 s {@code SO_TIMEOUT} mirror. */
    private static final int READ_DEADLINE_MILLIS = 60_000;
    /** Timer granularity — the server endpoint's value. */
    private static final long TIMER_GRANULARITY_MILLIS = 15L;
    /** Bound on the goodbye CTRL flush + BYE-echo wait (the endpoint's unarmed-flush mirror). */
    private static final long GOODBYE_BOUND_MILLIS = 5_000L;
    static final int RECEIVE_BUFFER_BYTES = 2_048;

    private final long shipId;
    /** The v2 WELCOME's {@code boundShipId} tail, stored only (the future N>2
     * ship-adopt lane's carrier; no consumer today). Volatile: adopted on connect + re-admit. */
    volatile long serverBoundShipId;
    /** The v4 WELCOME's {@code u8 serveSchemaMax} tail — the serve-side AWSS schema ceiling,
     * session-scoped. Volatile: adopted on connect +
     * re-admit; {@link PredictDatagramDial#schemaCompareAtAdmission} runs at each adoption. */
    volatile int serveSchemaMax;
    private final int selectedWeaponGroup;
    /** NOT final — {@link #recreateSocketForEscalation} swaps in a fresh socket
     * (new source port ⇒ fresh NAT/hairpin mapping both ways) after
     * {@value #RE_HELLO_SOCKET_RECREATE_EVERY_ESCALATIONS} fruitless nonce generations. Swapped
     * under {@code sendLock} (every send holds it); the reader compares its per-iteration
     * snapshot against the field after a receive failure, so a swap reads as "re-arm on the new
     * socket", never a reader death. */
    private volatile DatagramSocket socket;
    private final InetSocketAddress server;
    /** Adopted from WELCOME; REBOUND at re-admission (B4). Volatile: the reader's session filter
     * reads it per datagram; each reliability core's sink stamps the value read at its mint
     * ({@link #newReliabilityCore}), the envelope-only sends the live value. */
    volatile long sessionId;
    /** Swapped for a FRESH core at re-admission (B4) — the server minted a fresh session, so both
     * seq spaces restart; the old core dies with it. While RECONNECTING no thread feeds either
     * core (inbound drops before the core, sends drop at entry), so the swap never races a use —
     * except the late-generation adopt, which swaps while LIVE: a retired core's late emission then
     * leaves under its own dead id ({@link #newReliabilityCore}). */
    private volatile DatagramReliability reliability;
    final Thread reader;
    final Thread timer;
    /** B4 re-HELLO credentials — connect()'s own, held for the standing re-admit loop
     * (per the design freeze: same credentials, same socket; the token VALUE is never logged). */
    private final String clientId;
    private final String seatToken;
    /** The credential field {@link #seatToken} came from (the version-13
     * envelope slot; nothing a player or host sets). Held for the version-13 re-HELLO pump
     * ({@link #hs13}) — the session never sends a version-12 re-HELLO (item 44; none exists). */
    private final Handshake13Flight.Lane lane;
    /** The version-13 re-HELLO flights — ALWAYS built since THE FLIP (
     * the dial speaks only version 13).
     * Package-private for the selftest rows' {@code slotsForRows()} read only. */
    final Hs13ReHelloFlights hs13;
    /** The dial's ONE {@link SecureRandom} (the class doc): every re-HELLO nonce draws here, and every {@code /auth}
     * proof's ephemeral scalar ({@link #sendAuth13}). */
    private final SecureRandom rng;
    /** The {@code /auth} proof counter, ONE per PRDS life (1..2³²−1; past
     * {@link Handshake13Auth#COUNTER_MAX} every proof reads UNAVAILABLE). Never reset by a context swap: a restart
     * would let a stale proof's counter swallow the next honest one at a door whose {@code highest} it already raised.
     * */
    private final java.util.concurrent.atomic.AtomicLong auth13Counter = new java.util.concurrent.atomic.AtomicLong();
    /** The version-13 dial's flight (null at version 12), the
     * session's first kept flight — set by the dial before the daemons start, copied ONCE into
     * {@link #hs13}'s KEPT slot (by the dial itself, under {@code lock}, before the reader can run —
     * {@code PredictDatagramDial.connect}; the seed is once, so the timer's
     * first pass only fills the held unit); the field keeps it, and the
     * timer thread's exit abandons it OUTSIDE {@code lock}; {@link #close()} never calls it (it
     * can run on the frame thread, the flight's CALLER LAW forbids that). */
    Handshake13Flight dialFlight;
    private final String fingerprint;
    /** The joiner's initial-spawn variant pick, the HELLO v8
     * tail — "" = server default. Same value on every re-HELLO (a rejoin's pick is ignored
     * server-side; the door seeds on a FRESH claim only). */
    private final String variantId;
    /** THIS session admitted as an OBSERVER — HELLO (initial + every re-HELLO)
     * carries {@link HandshakeCodec#HELLO_FLAG_OBSERVER}; the WELCOME must answer with
     * {@link HandshakeCodec#WELCOME_FLAG_SEAT_OBSERVER} + boundShipId 0 (fail-closed both
     * ways at {@link #connect}). Never a knob read here: the caller ({@code CcClientModPlugin}
     * via {@code ObserverMode.ON}) decides the posture once at application load. */
    private final boolean observer;
    /** Inbound-silence bound (default {@value #READ_DEADLINE_MILLIS}); the test-facing connect
     * overload shortens it so the game-free kick gate sees a silence→re-admit cycle in test time. */
    private final int readDeadlineMillis;

    /** One inflater per session; with tick-conflation OFF it runs at the drain
     * (consumer thread), with it ON at delivery (reader thread) — one thread at a time either
     * way; its own monitor orders decode against close()'s end(). Sandbox-clean:
     * {@code java.util.zip} is not on the classloader-B deny list. */
    private final java.util.zip.Inflater snapInflater = SnapDeflateCodec.newInflater();

    /** Guards the CTRL queue, the SNAP slots, and the round flip; wakes the blocked take. */
    final Object lock = new Object();
    private final ArrayDeque<byte[]> ctrlQueue = new ArrayDeque<>();
    /** The CTRL lane was the ONE uncapped inbound queue. Drained only by combat's
     * {@link #receiveSnapshot}, it parks the whole admission burst (≤256 {@code VDEF} +
     * listings + posture rows — what {@link #peekVariantDefinitions} copies + decodes on the
     * menu thread) AND every combat-time fan (AIMP/MOVR ≈8 Hz per ship, fanned to every LIVE
     * session, combat entered or not — a menu-sit on a busy server piles them up un-drained).
     * Bounded allocation on wire input (security doctrine): past the cap a NEW payload is
     * refused + counted (the sibling idiom: {@link #PHASE_DIVE_QUEUE_CAP} …), the session
     * survives, and the FIRST refusal announces once (seam {@code ctrl-queue-cap}). Sized ≥16×
     * the legit admission burst; ≤1200 B per payload ⇒ ≤~4.9 MB worst case.
     * {@link #ctrlQueueHighWater} meters the real backlog at the teardown receipt so a re-size
     * rests on flights, not guesses. */
    private static final int CTRL_QUEUE_CAP = 4096;
    private int ctrlQueueHighWater; // guarded by lock
    private volatile int ctrlFramesDropped; // written under lock; volatile for the lock-free receipt read
    /** Snap-freshness state (tick-group slots, stale-fold, passthrough
     * side-deque, lever + lane counters) — the extracted seam. NOT self-locking:
     * every poll/deliver/flip/clear call below runs under {@code lock} (its class doc); its
     * volatile counters back this class's public receipt accessors lock-free, unchanged. */
    private final SnapFreshnessBuffer freshness;

    /** Written only under {@code lock} (the drain-time flip); volatile so the core's emission
     * sink stamps outbound envelopes without taking it (a momentarily-stale stamp is benign —
     * the server does not gate on inbound roundId). */
    private volatile int currentRoundId;
    private volatile long lastValidArrivalMillis;
    private volatile IOException readerFailure;
    volatile IOException sessionFailure;
    private volatile boolean peerSaidBye;
    private volatile boolean byeSent;
    private volatile boolean byeAcked;
    volatile boolean closed;
    private boolean closeStarted; // guarded by closeLock (close() idempotency)
    private final Object closeLock = new Object();

    // ---- B4 client re-HELLO loop (freeze): session death arms a standing re-admit on the
    // SAME socket instead of dying; LIVE again at this episode's admitted WELCOME. Episode state
    // guarded by lock; the flag is volatile for the presentation/send fast paths. ----
    volatile boolean reconnecting;
    private volatile long reconnectArmedAtMillis; // Episode arm stamp (nowMillis clock)
    long nextReHelloAtMillis; // guarded by lock (version 13's reader SEND pulls it forward, Hs13ReHelloFlights)
    /** The fresh-COOKIE give-back (slot returned +
     * doubling restarted) was already spent in the CURRENT nonce generation. At most ONE per
     * generation, so a per-packet-rebinding NAT (every re-HELLO re-challenged with a new
     * cookie) cannot pin the B3 escalation open. Cleared wherever a generation is minted
     * (episode arm, W5-D5 escalation). Guarded by lock. */
    private boolean reconnectGiveBackSpent;
    private long reHelloIntervalMillis; // guarded by lock (250 ms doubling to the 1 s cap)
    long reconnectNonceSends; // guarded by lock (sends of the CURRENT nonce generation)
    long reconnectEscalateAfterSends; // guarded by lock (doubles per escalation, capped)
    private volatile long reconnectNonceEscalations;
    volatile long reHelloEpisodes;
    private volatile long reHellosSent;
    /** The initial dial's wall-clock HELLO
     * retransmits and pre-admission silent drops (non-WELCOME / foreign-source / undecodable /
     * wrong-nonce), set once at construction from {@code connect}. */
    volatile int connectHelloRetransmits;
    volatile int connectPreAdmitDrops;
    /** Door COOKIE challenges (own nonce) seen by the
     * initial dial / by the B4 re-HELLO pump (the pre-admit meter family). */
    volatile int connectCookieChallenges;
    volatile long reHelloCookieChallenges;
    /**
     * The version-13 re-HELLO pump's challenges that left no
     * other trace — a {@code HELD} at the flight's SEAL_CAP / by the pair budget (before, the reader returned
     * uncounted), and r2's seals (CURRENT sealed a challenge that answered PREVIOUS; each is ALSO one
     * {@code reHelloCookieChallenges}). Counts ≥ 0. The READER thread is the single writer
     * ({@code Hs13ReHelloFlights.onReconnectWelcome}; the sibling count in the challenge count's own lock hold); the
     * plugin's ended line reads them (NOT the escalate row: {@code FallbackAnnounce} cuts a detail at 200 characters,
     * so that row's tail never prints). Version 12 never moves them. Delete trigger: they
     * live as long as the version-13 re-HELLO pump does.
     */
    volatile long reHelloHeldSealCap;
    volatile long reHelloHeldBudget;
    volatile long reHelloSiblingSeals;
    /**
     * The initial version-13 dial's {@code HELD} answers (seal cap or pair
     * budget; before, folded into {@link #connectPreAdmitDrops}), set once at construction from {@code connect}. ≥ 0;
     * 0 at version 12. Delete trigger: lives as long as the version-13 dial's flight does.
     */
    volatile int connectHeld;
    /** Fresh COOKIEs past the generation's one
     * give-back (answered with the cookied re-send; slot + doubling kept). */
    private volatile long reHelloCookieGiveBacksSuppressed;
    private volatile long reAdmits;
    /** Ms of inbound silence before the current episode armed (presentation). */
    private volatile long reconnectSilentBeforeArmMillis;
    /** this episode is the DELIBERATE re-baseline (armReAdmission, fallbackReason
     * null — a mission re-entry / ESC-then-Play re-pick), never a fallback (silence/stall/receive-fail).
     * Presentation reads it to mute the LOST/RECONNECTED pair on a rejoin the player asked for. */
    private volatile boolean reconnectDeliberate;
    // ---- B1/B2/B3: the deaf-episode instrumentation + the two
    // levers. The wedge had THREE silent drop paths (welcome source gate, nonce-rotation
    // race, welcomes never arriving) and no row that told them apart; every counter below rides
    // the per-episode escalation row + the first-WELCOME row so the next occurrence is a
    // one-look diagnosis. ----
    private long episodeEscalations; // guarded by lock (resets at arm; the B3 recreate clock)
    volatile long episodeWelcomesSeen; // reader thread; reset at arm
    private volatile long readerReceives; // reader thread: datagrams the socket handed back
    // The reader thread's OWN stall meter — the accused mechanism of the
    // storm read (a 64 KiB SO_RCVBUF holds ~55 datagrams; a reader that stays out of
    // receive() longer than that much traffic overflows it, and no kernel counter says so).
    // busy = previous return -> next receive() call (decode + core delivery + freshness insert,
    // plus any STW pause inside); gap = return -> return (the loop period; an idle lane widens
    // it too, so read it beside the traffic). Reader-thread writes only; volatile for readers.
    private volatile long readerBusyMaxNanos;
    private volatile long readerBusyOver50Ms; // busy segments >= 50 ms (count)
    private volatile long readerGapMaxNanos;
    // The same maxima over the WINDOW since the last F9 wire-mark took them —
    // the birth-to-now pair above is pinned by one cold-start / STW outlier for the whole flight,
    // so the threshold reads the window pair. Reader thread raises, the mark thread
    // takes-and-zeroes (getAndSet); a raise racing the take lands in the next window.
    private final java.util.concurrent.atomic.AtomicLong readerBusyWindowMaxNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong readerGapWindowMaxNanos =
            new java.util.concurrent.atomic.AtomicLong();
    /** The per-SNAP inter-arrival gap histogram + stall deque + local-stall
     * discriminator (reader thread writes; the F9 wire row / flush / teardown read). Instrument
     * only — nothing steers on it. */
    private final SnapGapMeter snapGap = new SnapGapMeter();
    volatile String lastWelcomeSource = "";
    volatile int lastWelcomeStatus = -1;
    volatile long prevGenerationAdopts;
    private volatile long lateGenerationAdopts;
    private volatile long socketRecreates;
    /** L-R1: the server's per-BOOT life nonce from the latest WELCOME (v6 u64
     * tail) — set at BOTH welcome lanes (initial connect + the re-admit adopt, {@link #adoptWelcomeLocked}); the
     * plugin compares it against its bound life at the re-admit edge. Never 0 once admitted
     * (the v6 decoder rejects 0). */
    volatile long serverLife;
    private volatile long reconnectInboundDropped;
    private volatile long sendsDroppedReconnecting;
    private volatile long reconnectByeEchoesSwallowed; // W5-D5: own-echo BYEs mid-reconnect

    // ---- Deliberate re-baseline: a LIVE keep-open at mission exit latches
    // missionExitedKeptOpen; the next mission init consumes it (armReAdmission) — fresh-nonce
    // re-HELLO (server: same-token TAKEOVER, fresh sessionId ⇒ the newly-live catch-up re-fires)
    // plus ownAuthorityHeld dropping UP intents until the plugin's fresh-world adopt releases it
    // (resumeOwnAuthority), so the writer's deploy-default facing never becomes server truth. ----
    private volatile boolean missionExitedKeptOpen; // latch: set at exit, consumed under lock at arm
    // ---- W5-D1: a kept-open mission exit BIDS GOODBYE — the in-game leave-match
    // path must TRUE-vacate the seat (server grace enters, cautious AI flies the hull) instead
    // of silently pumping an abandoned session; the re-pick then lands as RECLAIM, never a
    // displacement of a still-live session. missionExitByeSent suppresses the peerSaidBye latch
    // for the echo of OUR OWN mission-exit BYE (a latched echo would defunct the session and
    // kill the B4 re-admit lane); cleared at the next re-admit WELCOME adopt. ----
    private volatile boolean missionExitByeSent;
    private volatile long missionExitByeDeadlineMillis; // resend bound (0 = no mission-exit bye live)
    private volatile boolean ownAuthorityHeld; // volatile: the writer-thread send fast path reads it

    /** Envelope roundId of the datagram being fed to the core — reader thread only, valid inside
     * the synchronous delivery callbacks (round-barrier gating; the core is roundId-blind). */
    private int readerRoundId;

    // ---- counters (volatile: reader-thread written; plain int: consumer/writer thread, the
    // TCP session's discipline) ----
    private volatile long droppedUndecodable;
    private volatile long droppedBadSession;
    volatile long droppedBadSource;
    private volatile long droppedUnexpectedChannel;
    volatile long welcomeDuplicates;
    private ServerSnapshotFrame lastSnapshot;
    private int framesSent;
    private int despawnFramesIgnored;
    private int matchResetFramesIgnored;
    private int seatNamesFramesIgnored;
    private int seatNameRecordFramesIgnored;
    private int hulkFractureFramesIgnored;
    private int hulkPoseFramesIgnored;
    private int hulkCutGeometryFramesIgnored;
    private int chatFramesIgnored;
    private int rosterAppendFramesIgnored;
    private int reservedCtrlIgnored;

    /** The voice orchestrator, session-owned (its lifetime = ours; a B4
     * re-admit keeps it and re-seeds the WELCOME bit). Uplink = {@link #sendVoice}. */
    final VoiceClient voice = new VoiceClient(this::sendVoice);
    /** VOICE-channel envelope seq: ONE monotonic counter (the relay drops a non-advancing seq). */
    private final java.util.concurrent.atomic.AtomicLong voiceSeq =
            new java.util.concurrent.atomic.AtomicLong();
    /** Serializes the raw socket send between the reliability core (under its monitor) and the
     * capture thread's {@link #sendVoice} — never held around the read loop. */
    private final Object sendLock = new Object();

    /** Latest full-state seat table, materialized off drained {@code SEAT} CTRL
     * frames (consumer thread writes, presentation reads — guarded by the map's own monitor;
     * per-seat newest {@code seatSeq} wins). Presentation consumes {@link #seatStateTable()}
     * (D4.5); nothing else in the certified 2-seat lane touches it (legacy serves never emit
     * {@code SEAT}). */
    private final TreeMap<Integer, ClientSeatStateEvent> seatStateBySeat = new TreeMap<>();
    private int seatStateFramesReceived; // guarded by seatStateBySeat
    /** The seat of the newest APPLIED {@code SEAT} event that
     * carried THIS session's hull ({@link #shipId} &gt; 0), or -1 = not seen yet / observer. Latched (not
     * re-derived from the newest-per-seat row) so a dead-latched own seat — {@code shipId 0} while it waits
     * to respawn — still names its seat. Written under {@link #seatStateBySeat}; read lock-free. */
    private volatile int ownSeatId = -1;

    /** The serve-end {@code MOVR} verdict, latched first-wins off the drained
     * CTRL lane (consumer thread writes, presentation reads — guarded by {@code matchOverLock});
     * session-terminal, never cleared. Presentation polls {@link #matchOverRecord()}. */
    private final Object matchOverLock = new Object();
    private ClientMatchOverRecord matchOverRecord; // guarded by matchOverLock
    private int matchOverFramesReceived; // guarded by matchOverLock

    /** The HOUS KICKED-notice reason, latched first-wins off the drained CTRL
     * lane (consumer thread writes, presentation reads — guarded by {@code kickedNoticeLock});
     * -1 until mailed; EPISODE-terminal: cleared ONLY by the deliberate {@link #armReAdmission}
     * mission re-entry re-dial (D4b — the session object survives a kick, so a never-cleared
     * latch would re-fire the kicked UX on the fresh post-rejoin mission). Polled via
     * {@link #kickedNoticeReason()}. */
    private final Object kickedNoticeLock = new Object();
    private int kickedNoticeReason = -1; // guarded by kickedNoticeLock
    private int kickedNoticeFramesReceived; // guarded by kickedNoticeLock
    /** kicked-TERMINAL, the reason latch mirrored as a lock-free flag: while set,
     * the automatic B4 re-HELLO stays refused ({@link #armReconnectLocked}; latching also CANCELS
     * an already-armed episode) and intent/CTRL/voice sends drop
     * (a kick is deliberate removal from the round — the anti-feature was the 60 s
     * silence path auto-re-admitting the victim). Deliberately NOT in {@link #isDefunct}: the
     * app-scoped session stays dialable, so a deliberate mission re-entry rejoins via the normal
     * {@link #armReAdmission} fresh-nonce TAKEOVER lane, which also clears this. */
    volatile boolean kickedTerminal;

    /** {@code DIVE} phase-dive-start records, queued off the drained CTRL
     * lane (consumer thread appends, presentation drain-takes — guarded by the inbox's own monitor —);
     *
     * bounded — a flood past the cap drops-with-count, never grows (wire-driven allocation stays
     * capped). Presentation polls {@link #takePhaseDives()}.
     * The lock/deque/counters collapsed into one
     * {@link BoundedInbox} (its monitor replaces {@code phaseDiveLock}). */
    private static final int PHASE_DIVE_QUEUE_CAP = 64;
    private final BoundedInbox<ClientPhaseDive> phaseDiveInbox = new BoundedInbox<>(PHASE_DIVE_QUEUE_CAP);

    /** {@code VDEF} variant definitions, queued off the drained CTRL lane
     * (consumer thread appends, the plugin's fold lane drain-takes — guarded by
     * the inbox's own monitor); bounded — a flood past the cap drops-with-count, never
     * grows (wire-driven allocation stays capped). The cap is the server's
     * ONE admission budget ({@code VariantDefinitionFrameCodec.VDEF_BUDGET_TOTAL} 960 = boot catalog
     * 256 + customs 64 + live wave bodies 640) plus 64 margin for a re-admission burst arriving
     * before the plugin's next fold — a literal here (no shared class across the trees), pinned
     * against the codec by {@code CcClientVdefVerifier} (it was a 320
     * literal sized on the old 256 catalog bound, and the wave lane's raise would have overrun it
     * silently). The fold lane reads {@link #variantDefinitionFramesDropped()} and logs the first
     * drop. Presentation polls {@link #takeVariantDefinitions()}.
     * Collapsed into one {@link BoundedInbox} (its monitor
     * replaces {@code variantDefinitionLock}). */
    private final BoundedInbox<ClientVariantDefinition> variantDefinitionInbox =
            new BoundedInbox<>(VARIANT_DEFINITION_QUEUE_CAP);
    /** W15 v0: the {@code VACK} receipt queue — the VDEF idiom (bounded, count-and-
     *  drop past the cap; ONE submission per connect makes the cap a spoof/pile-up bound only).
     *  One {@link BoundedInbox}. */
    private static final int VARIANT_RECEIPT_QUEUE_CAP = 64;
    private final BoundedInbox<ClientVariantReceipt> variantReceiptInbox =
            new BoundedInbox<>(VARIANT_RECEIPT_QUEUE_CAP);
    private final java.util.concurrent.atomic.AtomicInteger variantSubmitSeq =
            new java.util.concurrent.atomic.AtomicInteger(); // u16 wrap at encode
    /** The seqs sent keep-only, one bit per u16 seq (8 KiB, fixed); set at the send, cleared by the receipt lane's
     *  {@link #takeKeepOnlyMark} or by a later plain send on the wrapped seq. */
    private final java.util.BitSet keepOnlySeqs = new java.util.BitSet(VariantSubmitWriter.SUBMIT_SEQ_MAX + 1);
    public static final int VARIANT_DEFINITION_QUEUE_CAP = 1024;

    /** The {@code PSTB} posture wire block, latched LATEST-WINS off the
     * drained CTRL lane (consumer thread writes, the combat plugin drain-takes — guarded by
     * {@code postureBlockLock}). Latest-wins, not first-wins: a re-admission spools a fresh
     * block whose server-assigned tag may differ, and the newest stamp is the truth; the plugin's
     * take-clears poll makes each latched block dump exactly once. Bounded by construction
     * (one slot). Presentation polls {@link #takePostureBlockOrNull()}. */
    private final Object postureBlockLock = new Object();
    private ClientPostureBlock postureBlock; // guarded by postureBlockLock
    private int postureBlockFramesReceived; // guarded by postureBlockLock

    /** {@code MMNT} module-mount handovers, queued off the drained CTRL lane
     * (consumer thread appends, the plugin's mount lane drain-takes — guarded by
     * the inbox's own monitor); bounded — a flood past the cap drops-with-count, never grows
     * (wire-driven allocation stays capped). Presentation polls
     * {@link #takeModuleMounts()}.
     * One {@link BoundedInbox} (replaces {@code moduleMountLock}). */
    private static final int MODULE_MOUNT_QUEUE_CAP = 64;
    private final BoundedInbox<ClientModuleMount> moduleMountInbox =
            new BoundedInbox<>(MODULE_MOUNT_QUEUE_CAP);

    /** {@code XFER} command-transfer control edges, queued off the drained
     * CTRL lane (consumer thread appends, the plugin drain-takes — guarded by
     * the inbox's own monitor); bounded — a flood past the cap drops-with-count, never grows
     * (wire-driven allocation stays capped). Presentation polls
     * {@link #takeCommandTransfers()} (count-only until the L4 apply leg).
     * One {@link BoundedInbox} (replaces {@code commandTransferLock}). */
    private static final int COMMAND_TRANSFER_QUEUE_CAP = 64;
    private final BoundedInbox<ClientCommandTransfer> commandTransferInbox =
            new BoundedInbox<>(COMMAND_TRANSFER_QUEUE_CAP);

    /** {@code AIMP} batched pilot-aim frames, queued off the drained
     * CTRL lane (consumer thread appends, the plugin's remote-aim lane drain-takes — guarded by
     * the inbox's own monitor); bounded — a flood past the cap drops-with-count, never grows
     * (wire-driven allocation stays capped). The cap covers a render-side
     * stall over the ≈8 Hz fan cadence; a dropped frame self-heals at the next fan/keepalive.
     * Presentation polls {@link #takePilotAims()}.
     * One {@link BoundedInbox} (replaces {@code pilotAimLock}). */
    private static final int PILOT_AIM_QUEUE_CAP = 64;
    private final BoundedInbox<ClientPilotAimFrame> pilotAimInbox =
            new BoundedInbox<>(PILOT_AIM_QUEUE_CAP);

    //
    /** {@code ASTP} batched asteroid-pose frames, queued off the drained CTRL
     * lane (consumer thread appends, the plugin's asteroid lane drain-takes -- guarded by
     * the inbox's own monitor); bounded -- past {@link #ASTEROID_QUEUE_CAP} un-taken frames the flood
     * drops-with-count, never grows. The cap covers a join sweep (&lt;= 2
     * frames/tick) across a render-side stall; a dropped ALIVE is re-converged by the server's
     * round-robin REFRESH row (ONE body per refresh tick -- with N bodies, within about N x 6
     * ticks). A dropped DESTROYED/EXPIRED row is NEVER re-sent, so the death rows of a capped
     * frame ride past the cap as a death-only frame
     * up to {@link #ASTEROID_DEATH_QUEUE_EXTRA} more frames -- the bound is a SIZING choice
     * (512 = 2x {@link #ASTEROID_QUEUE_CAP}), a TIME bound, not a structural limit: the keeper
     * expires and respawns rocks with fresh ids, so deaths grow with a stall's length;
     * past it the death rows drop with their own count (a ghost twin = a FINDING on the flight sidelog).
     * Presentation polls {@link #takeAsteroidPoses()}.
     * One {@link BoundedInbox} (its monitor replaces
     * {@code asteroidLock}; received/dropped = ANY row lost at the cap live in the inbox, the
     * death-row keep counters stay here under the same monitor). */
    private static final int ASTEROID_QUEUE_CAP = 256;
    private static final int ASTEROID_DEATH_QUEUE_EXTRA = 512;
    private final BoundedInbox<ClientAsteroidFrame> asteroidInbox =
            new BoundedInbox<>(ASTEROID_QUEUE_CAP);
    private int asteroidDeathFramesKept; // guarded by asteroidInbox (its monitor) (death-only frames past the cap)
    private int asteroidDeathRowsDropped; // guarded by asteroidInbox (its monitor) (death rows lost = ghost twins)
    /** The round epoch, widened to the ctrl
     * round epoch: +1 per drained MRST, written on the NET THREAD only (routeCtrl's MATCH_RESET
     * case); stamped on ASTP frames ({@link #recordAsteroidPose}) and on RAPP + MRST frames at
     * intake (routeCtrl's ROSTER_APPEND / MATCH_RESET cases, the MRST AFTER the increment). A
     * per-session-life counter -- deliberately NOT {@code currentRoundId}, which wraps at 0xFF
     * -- so the render-side lanes order frames against the round flip by
     * stream order with no drain-timing assumption. Volatile: the render thread reads it once at
     * the plugin bind ({@link #ctrlRoundEpoch()} -- the roster-append lane's seed). */
    private volatile int ctrlRoundEpoch;

    /** {@code LSTD} structured-listing frames, queued off the drained
     * CTRL lane (consumer thread appends, the picker lane drain-takes — guarded by
     * the inbox's own monitor); bounded — a flood past the cap drops-with-count, never grows
     * (wire-driven allocation stays capped). The cap holds two full
     * max-cap drips (MAX_SEQ_COUNT=255 frames — the old 64 cap would have
     * dropped frame 65 of the 113-frame debug catalogue and fail-closed EVERY over-64-frame
     * listing at assembly); a dropped frame fail-closes the WHOLE listing (never a partial
     * render) and the player simply re-asks. Presentation polls {@link #takeListings()}.
     * One {@link BoundedInbox} (replaces {@code listingLock}). */
    private static final int LISTING_QUEUE_CAP = 512;
    private final BoundedInbox<ClientListingRecord> listingInbox =
            new BoundedInbox<>(LISTING_QUEUE_CAP);

    PredictReconcileDatagramSession(long shipId, int selectedWeaponGroup,
            DatagramSocket socket, InetSocketAddress server, long sessionId, int startingRoundId,
            String clientId, String seatToken, Handshake13Flight.Lane lane, int handshakeVersion,
            SecureRandom rng, String fingerprint, String variantId,
            int readDeadlineMillis, boolean observer, byte[] keymapOrNull) {
        this.shipId = shipId;
        this.observer = observer;
        this.selectedWeaponGroup = selectedWeaponGroup;
        this.socket = socket;
        this.server = server;
        this.sessionId = sessionId;
        this.currentRoundId = startingRoundId;
        this.clientId = clientId;
        this.seatToken = seatToken;
        this.lane = lane;
        this.rng = rng;
        this.fingerprint = fingerprint;
        this.variantId = variantId;
        this.readDeadlineMillis = readDeadlineMillis;
        // Always built - handshakeVersion stays on the ctor (the dial already refused
        // anything but 13; the parameter's drop is parked) and picks nothing here
        // the dial's keymap rides every re-HELLO (Hs13ReHelloFlights keeps its own copy)
        this.hs13 = new Hs13ReHelloFlights(this, lane,
                seatToken, observer, fingerprint, clientId, shipId, variantId, keymapOrNull, server, rng);
        boolean snapTickConflate =
                resolveLeverKnob("cc.predict.snapTickConflate", "CC_PREDICT_SNAP_TICK_CONFLATE");
        // Composition law: staleFold requires tickConflate — a stale payload must never
        // enter the one-slot buffer, so tickConflate=false silently reverts BOTH levers
        // (short-circuit: a child lever's knob is never even parsed with its parent OFF).
        boolean snapStaleFold = snapTickConflate
                && resolveLeverKnob("cc.predict.snapStaleFold", "CC_PREDICT_SNAP_STALE_FOLD");
        // Composition law: passthrough requires staleFold (transitively
        // tickConflate) — either parent OFF reverts the rung to the refusal-drop today-path.
        boolean snapStalePassthrough = snapStaleFold && resolveLeverKnob(
                "cc.predict.snapStalePassthrough", "CC_PREDICT_SNAP_STALE_PASSTHROUGH");
        this.freshness =
                new SnapFreshnessBuffer(snapTickConflate, snapStaleFold, snapStalePassthrough);
        this.reliability = newReliabilityCore();
        this.lastValidArrivalMillis = nowMillis();
        this.reader = new Thread(this::readLoopBelted, "cc-predict-datagram-reader"); //
        this.reader.setDaemon(true);
        this.timer = new Thread(this::timerLoopBelted, "cc-predict-datagram-timer"); //
        this.timer.setDaemon(true);
    }

    /**
     * Admit at {@code host:port} (HELLO retransmit every {@value #HELLO_RETRY_MILLIS} ms until a
     * nonce-matching WELCOME or {@value #CONNECT_TIMEOUT_MILLIS} ms) and start the
     * session threads. Credentials come from the match descriptor's own seat row
     * ({@code CcClientModPlugin} resolves them — fail-closed, no blank-token door from the mod).
     * Fail-closed: on any failure the socket is closed and the cause rethrown, so the caller's
     * retry loop / LOCAL-VANILLA fallback behaves exactly as the TCP connect's.
     * <p>Every overload carries {@code lane} + {@code handshakeVersion} right after the credential
     * (each caller states both; production passes
     * {@link Handshake13#VERSION}, the only version the dial accepts) — the law lives on the one body,
     * {@code PredictDatagramDial.connect}.
     */
    public static PredictReconcileDatagramSession connect(String host, int port, long shipId,
            int selectedWeaponGroup, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint)
            throws IOException {
        return connect(host, port, shipId, selectedWeaponGroup, clientId, seatToken, lane,
                handshakeVersion, fingerprint,
                "", resolveSilenceDeadlineMillis());
    }

    /** The in-game entry: the no-deadline overload carrying the joiner's
     * initial-spawn {@code variantId} pick ("" = server default) into the HELLO v8 tail. */
    public static PredictReconcileDatagramSession connect(String host, int port, long shipId,
            int selectedWeaponGroup, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint,
            String variantId) throws IOException {
        return connect(host, port, shipId, selectedWeaponGroup, clientId, seatToken, lane,
                handshakeVersion, fingerprint,
                variantId, resolveSilenceDeadlineMillis(), false);
    }

    /** The in-game entry with the OBSERVER posture — {@code observer=true}
     * requires {@code shipId == 0} (an observer holds no seat; IAE BEFORE any datagram), puts
     * {@link HandshakeCodec#HELLO_FLAG_OBSERVER} on the HELLO and accepts ONLY a WELCOME carrying
     * {@link HandshakeCodec#WELCOME_FLAG_SEAT_OBSERVER} + boundShipId 0 — a bit-less WELCOME
     * (an older server) is a TERMINAL {@link AdmissionRejected} ({@code observer_not_granted}),
     * never a silent pilot admit. {@code observer=false} is byte-identical to the overload above. */
    public static PredictReconcileDatagramSession connect(String host, int port, long shipId,
            int selectedWeaponGroup, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint,
            String variantId, boolean observer) throws IOException {
        return connect(host, port, shipId, selectedWeaponGroup, clientId, seatToken, lane,
                handshakeVersion, fingerprint,
                variantId, resolveSilenceDeadlineMillis(), observer);
    }

    /** (83) CL-14 — the in-game inbound-silence deadline, knob {@code cc.predict.udpSilenceMs}
     * (default {@value #READ_DEADLINE_MILLIS} ms — behavior unchanged when unset; malformed or
     * non-positive keeps the default with one announce, the N2 idiom). */
    private static int resolveSilenceDeadlineMillis() {
        int v = cc.starsector.client.KnobParse.intOr("cc.predict.udpSilenceMs",
                READ_DEADLINE_MILLIS, "udp-silence-knob-bad");
        if (v <= 0) {
            FallbackAnnounce.announceOnce("udp-silence-knob-bad#nonpositive",
                    "cc.predict.udpSilenceMs=" + v + " non-positive -> keeping "
                            + READ_DEADLINE_MILLIS + " ms");
            return READ_DEADLINE_MILLIS;
        }
        return v;
    }

    /** The LIVE socket's effective SO_RCVBUF (re-read after a B3 re-create) — the plugin's
     * OPEN row carries it; -1 when unreadable. */
    @Override
    public int udpReceiveBufferBytes() {
        return PredictDatagramDial.effectiveReceiveBufferBytes(socket);
    }

    /** The reader thread's longest stay OUT of receive() so far (ms) — the
     * direct witness of the accused stall (a 64 KiB SO_RCVBUF ≈ 55 datagrams of cover). */
    public double readerBusyMaxMillis() {
        return readerBusyMaxNanos / 1e6d;
    }

    /** Reader busy segments ≥ 50 ms (count). */
    public long readerBusyOver50Ms() {
        return readerBusyOver50Ms;
    }

    /** The longest receive()-return to receive()-return period so far (ms; an idle
     * lane widens it too — read beside the traffic). */
    public double readerGapMaxMillis() {
        return readerGapMaxNanos / 1e6d;
    }

    /** The longest reader busy segment SINCE the previous take (ms) — the F9
     * wire-mark's window pair; taking it starts the next window. */
    public double takeReaderBusyWindowMaxMillis() {
        return readerBusyWindowMaxNanos.getAndSet(0L) / 1e6d;
    }

    /** The longest receive()-to-receive() period SINCE the previous take (ms). */
    public double takeReaderGapWindowMaxMillis() {
        return readerGapWindowMaxNanos.getAndSet(0L) / 1e6d;
    }

    /** The SNAP-lane gap instrument (read-only for callers; the reader
     * thread is its sole writer). */
    public SnapGapMeter snapGapMeter() {
        return snapGap;
    }

    /** Test-facing overload (B4 gate): a shortened silence deadline lets the game-free kick row
     * observe the udp_silence → re-HELLO → re-admit cycle in test time. In-game callers resolve
     * the {@code cc.predict.udpSilenceMs} knob via the no-deadline overload above — a deliberate
     * re-adjudication of the former "NOT a knob" stance. */
    public static PredictReconcileDatagramSession connect(String host, int port, long shipId,
            int selectedWeaponGroup, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint,
            int readDeadlineMillis) throws IOException {
        return connect(host, port, shipId, selectedWeaponGroup, clientId, seatToken, lane,
                handshakeVersion, fingerprint,
                "", readDeadlineMillis);
    }

    /** The pilot-posture body twin ({@code observer=false}) — kept so every older
     * caller (harness rows, the B4 gate) stays byte-identical. */
    public static PredictReconcileDatagramSession connect(String host, int port, long shipId,
            int selectedWeaponGroup, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint,
            String variantId, int readDeadlineMillis) throws IOException {
        return connect(host, port, shipId, selectedWeaponGroup, clientId, seatToken, lane,
                handshakeVersion, fingerprint,
                variantId, readDeadlineMillis, false);
    }

    /** The pre-W13-leg-1b body twin: the boot-dial HELLO horizon ({@value #CONNECT_TIMEOUT_MILLIS}
     * ms) — every pre-leg-1b caller (harness rows, the B4 gate) stays byte-identical. */
    public static PredictReconcileDatagramSession connect(String host, int port, long shipId,
            int selectedWeaponGroup, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint,
            String variantId, int readDeadlineMillis, boolean observer) throws IOException {
        return PredictDatagramDial.connect(host, port, shipId, selectedWeaponGroup, clientId, seatToken, lane,
                handshakeVersion, fingerprint,
                variantId, readDeadlineMillis, observer, CONNECT_TIMEOUT_MILLIS, null);
    }

    /** The CLICK-dial entry — the in-game silence deadline
     * plus a caller-chosen per-attempt HELLO horizon. The 15 s default is a boot-dial relic: the
     * click-started retry loop (150 attempts) turned it into a ~42 min give-up on a silent door
     * while the briefing row promised ~5; the click dial passes ~3 s and re-attempts instead
     * (the HELLO retransmits every {@value #HELLO_RETRY_MILLIS} ms inside either horizon, so a
     * late bind is caught just as fast). The loop also honours the calling thread's interrupt
     * between retransmits — a re-click on a MOVED endpoint cancels the in-flight dial. */
    public static PredictReconcileDatagramSession connect(String host, int port, long shipId,
            int selectedWeaponGroup, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint,
            String variantId, boolean observer, int helloTimeoutMillis) throws IOException {
        return connect(host, port, shipId, selectedWeaponGroup, clientId, seatToken, lane,
                handshakeVersion, fingerprint, variantId, observer, helloTimeoutMillis, null);
    }

    /** The CLICK-dial entry carrying the pilot's control keymap: {@code keymapOrNull} = the 16 bytes the HELLO seals
     * after the team byte (each 0 = unbound, 1..0xDF a key code, 0xF0..0xF7 a mouse button; null = 16 zeros = the
     * serve's own bindings answer). Read by the caller on the GAME thread; the session keeps a copy so every re-HELLO
     * re-seals the same map. An out-of-domain byte is the flight's IAE before any datagram.
     * */
    public static PredictReconcileDatagramSession connect(String host, int port, long shipId,
            int selectedWeaponGroup, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint,
            String variantId, boolean observer, int helloTimeoutMillis, byte[] keymapOrNull) throws IOException {
        return PredictDatagramDial.connect(host, port, shipId, selectedWeaponGroup, clientId, seatToken, lane,
                handshakeVersion, fingerprint,
                variantId, resolveSilenceDeadlineMillis(), observer, helloTimeoutMillis, keymapOrNull);
    }

    // ------------------------------------------------------------------ receive (consumer thread)

    @Override
    public ServerSnapshotFrame receiveSnapshot() throws IOException {
        return receiveInternal(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public ServerSnapshotFrame receiveSnapshot(Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent)
            throws IOException {
        return receiveSnapshot(onEdgeEvent, reset -> matchResetFramesIgnored++);
    }

    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, despawn -> despawnFramesIgnored++);
    }

    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, onDespawn, names -> seatNamesFramesIgnored++);
    }

    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames) throws IOException {
        if (onEdgeEvent == null || onReset == null || onDespawn == null || onSeatNames == null) {
            throw new IllegalArgumentException("consumers must not be null");
        }
        return receiveInternal(onEdgeEvent, onReset, onDespawn, onSeatNames, null,
                chat -> chatFramesIgnored++, rec -> seatNameRecordFramesIgnored++,
                hf -> hulkFractureFramesIgnored++, hp -> hulkPoseFramesIgnored++,
                cg -> hulkCutGeometryFramesIgnored++, ra -> rosterAppendFramesIgnored++);
    }

    /** The AWSD-aware receive — the only overload that admits the delta+keyframe
     * ship lane; every other receive fails closed on an AWSD arrival (D11 stale-kit posture). */
    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame) throws IOException {
        if (onEdgeEvent == null || onReset == null || onDespawn == null || onSeatNames == null
                || onAwsdFrame == null) {
            throw new IllegalArgumentException("consumers must not be null");
        }
        return receiveInternal(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame,
                chat -> chatFramesIgnored++, rec -> seatNameRecordFramesIgnored++,
                hf -> hulkFractureFramesIgnored++, hp -> hulkPoseFramesIgnored++,
                cg -> hulkCutGeometryFramesIgnored++, ra -> rosterAppendFramesIgnored++);
    }

    /** The chat-aware receive — {@code CHTD} relays route to {@code onChat} on this
     * consumer thread (the plugin's consumer queues for its render-thread HUD drain); every
     * other demuxed overload count-ignores chat ({@link #chatFramesIgnored} — presentation
     * traffic must never kill a session). */
    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat) throws IOException {
        if (onEdgeEvent == null || onReset == null || onDespawn == null || onSeatNames == null
                || onAwsdFrame == null || onChat == null) {
            throw new IllegalArgumentException("consumers must not be null");
        }
        return receiveInternal(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                rec -> seatNameRecordFramesIgnored++, hf -> hulkFractureFramesIgnored++,
                hp -> hulkPoseFramesIgnored++, cg -> hulkCutGeometryFramesIgnored++,
                ra -> rosterAppendFramesIgnored++);
    }

    /** The seat-name-record-aware receive — {@code SNMR} fans/catch-ups route to
     * {@code onSeatNameRecord} on this consumer thread (the plugin folds them into its wire-name
     * map); every other demuxed overload count-ignores the record
     * ({@link #seatNameRecordFramesIgnored} — names are presentation, dropping one must never
     * kill a session). */
    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord) throws IOException {
        if (onEdgeEvent == null || onReset == null || onDespawn == null || onSeatNames == null
                || onAwsdFrame == null || onChat == null || onSeatNameRecord == null) {
            throw new IllegalArgumentException("consumers must not be null");
        }
        return receiveInternal(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                onSeatNameRecord, hf -> hulkFractureFramesIgnored++,
                hp -> hulkPoseFramesIgnored++, cg -> hulkCutGeometryFramesIgnored++,
                ra -> rosterAppendFramesIgnored++);
    }

    /** The hulk-fracture-aware receive — {@code HKFR} fracture edges route to
     * {@code onHulkFracture} on this consumer thread (the plugin parks/applies them on the game
     * thread); every other demuxed overload count-ignores the edge
     * ({@link #hulkFractureFramesIgnored} — fracture is presentation, dropping one must never
     * kill a session). */
    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture) throws IOException {
        if (onEdgeEvent == null || onReset == null || onDespawn == null || onSeatNames == null
                || onAwsdFrame == null || onChat == null || onSeatNameRecord == null
                || onHulkFracture == null) {
            throw new IllegalArgumentException("consumers must not be null");
        }
        return receiveInternal(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                onSeatNameRecord, onHulkFracture, hp -> hulkPoseFramesIgnored++,
                cg -> hulkCutGeometryFramesIgnored++, ra -> rosterAppendFramesIgnored++);
    }

    /** The corpse-pose-aware receive — {@code HKPS} keyframes route to
     * {@code onHulkPose} on this consumer thread (the plugin queues them for its render-thread
     * pose adoption); every other demuxed overload count-ignores the keyframe
     * ({@link #hulkPoseFramesIgnored} — pose is presentation, dropping one must never kill a
     * session; the periodic cadence self-heals). */
    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture,
            Consumer<? super ClientHulkPoseRecord> onHulkPose) throws IOException {
        if (onEdgeEvent == null || onReset == null || onDespawn == null || onSeatNames == null
                || onAwsdFrame == null || onChat == null || onSeatNameRecord == null
                || onHulkFracture == null || onHulkPose == null) {
            throw new IllegalArgumentException("consumers must not be null");
        }
        return receiveInternal(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                onSeatNameRecord, onHulkFracture, onHulkPose,
                cg -> hulkCutGeometryFramesIgnored++, ra -> rosterAppendFramesIgnored++);
    }

    /** The cut-geometry-aware receive — {@code HKCG} records route to
     * {@code onHulkCutGeometry} on this consumer thread (the plugin latches them for its
     * render-thread carve apply); every other demuxed overload count-ignores the record
     * ({@link #hulkCutGeometryFramesIgnored} — cut geometry is presentation, dropping one
     * degrades that cut to the local no-arg fallback, never kills a session). */
    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture,
            Consumer<? super ClientHulkPoseRecord> onHulkPose,
            Consumer<? super ClientHulkCutGeometryRecord> onHulkCutGeometry) throws IOException {
        if (onEdgeEvent == null || onReset == null || onDespawn == null || onSeatNames == null
                || onAwsdFrame == null || onChat == null || onSeatNameRecord == null
                || onHulkFracture == null || onHulkPose == null || onHulkCutGeometry == null) {
            throw new IllegalArgumentException("consumers must not be null");
        }
        return receiveInternal(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                onSeatNameRecord, onHulkFracture, onHulkPose, onHulkCutGeometry,
                ra -> rosterAppendFramesIgnored++);
    }

    /** The roster-append-aware receive — {@code RAPP} mid-round appends route
     * to {@code onRosterAppend} on this consumer thread (the plugin queues them for its
     * render-thread registry append); every other demuxed overload count-ignores the frame
     * ({@link #rosterAppendFramesIgnored} — but unlike the presentation lanes a nonzero count on
     * the live plugin loop marks a caller that must be widened: a dropped append re-fields the
     * ghost match). A stale kit without this lane dies at its routeCtrl unknown-Kind throw —
     * fail-closed BY DESIGN (admission-D6). */
    @Override
    public ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture,
            Consumer<? super ClientHulkPoseRecord> onHulkPose,
            Consumer<? super ClientHulkCutGeometryRecord> onHulkCutGeometry,
            Consumer<? super ClientRosterAppend> onRosterAppend) throws IOException {
        if (onEdgeEvent == null || onReset == null || onDespawn == null || onSeatNames == null
                || onAwsdFrame == null || onChat == null || onSeatNameRecord == null
                || onHulkFracture == null || onHulkPose == null || onHulkCutGeometry == null
                || onRosterAppend == null) {
            throw new IllegalArgumentException("consumers must not be null");
        }
        return receiveInternal(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                onSeatNameRecord, onHulkFracture, onHulkPose, onHulkCutGeometry, onRosterAppend);
    }

    /** Drain queued CTRL to the consumers ({@code null} consumers = the plain no-demux receive,
     * which fails closed on ANY CTRL — its TCP twin's posture), then take a FRESH snapshot. */
    private ServerSnapshotFrame receiveInternal(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture,
            Consumer<? super ClientHulkPoseRecord> onHulkPose,
            Consumer<? super ClientHulkCutGeometryRecord> onHulkCutGeometry,
            Consumer<? super ClientRosterAppend> onRosterAppend) throws IOException {
        while (true) {
            byte[] ctrlPayload = null;
            byte[] snapPayload = null;
            boolean passthroughTake = false;
            synchronized (lock) {
                while (true) {
                    IOException dead = firstFailure();
                    if (dead != null) {
                        throw dead;
                    }
                    if (!ctrlQueue.isEmpty()) {
                        ctrlPayload = ctrlQueue.removeFirst();
                        break;
                    }
                    // Tick-group drain (conflation ON): the newest tick's chunks leave FIFO, ONE per
                    // call — the plugin loop folds each via merger.onFrame, so co-tick siblings
                    // land as successive merge folds; OFF takes the single latest-wins slot.
                    // fresh-first — group-refused stale chunks deliver only when no
                    // newest-tick chunk is waiting (passthrough must never delay fresh truth;
                    // the merger's per-tier guards make the late fold safe).
                    snapPayload = freshness.pollFreshLocked();
                    if (snapPayload == null) {
                        snapPayload = freshness.pollStalePassthroughLocked();
                        passthroughTake = snapPayload != null;
                    }
                    if (snapPayload != null) {
                        break;
                    }
                    if (peerSaidBye) {
                        return null; // server-initiated goodbye = the clean-EOF mirror
                    }
                    if (closed) {
                        throw new IOException("predict datagram session closed");
                    }
                    long silent = nowMillis() - lastValidArrivalMillis;
                    if (!reconnecting && !kickedTerminal && silent >= readDeadlineMillis) {
                        // B4 (freeze): the udp_silence death ARMS the standing re-HELLO
                        // instead of killing the session — this take keeps blocking (ships
                        // free-run on local prediction) until re-admission or match-end BYE.
                        // kickedTerminal (D4b) skips the arm AND takes the coarse wait below:
                        // silent stays past the deadline forever, so the fine branch would
                        // busy-spin at its 1 ms floor while the kicked UX plays out.
                        armReconnectLocked("udp_silence silent_ms=" + silent);
                    }
                    try {
                        lock.wait(reconnecting || kickedTerminal ? 100L
                                : Math.max(1L, Math.min(100L, readDeadlineMillis - silent)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("predict datagram receive interrupted", interrupted);
                    }
                }
            }
            if (ctrlPayload != null) {
                routeCtrl(ctrlPayload, onEdgeEvent, onReset, onDespawn, onSeatNames, onChat,
                        onSeatNameRecord, onHulkFracture, onHulkPose, onHulkCutGeometry,
                        onRosterAppend);
                continue; // all queued CTRL drains before any snapshot take
            }
            ServerStreamMessage message = decodePayload(snapPayload, "SNAP");
            if (message.isAwsdSnapshot()) {
                // Ships ride the AWSD delta+keyframe lane when the server's
                // snapDelta is ON. Routed via consumer (the demux-consumer template; runs outside
                // the lock like routeCtrl) — the take keeps draining, and the next AWSS frame
                // (the ship-empty heartbeat under L3) still paces the return. A stale-passthrough
                // AWSD take routes identically: the merger's per-tier tick guards make the late
                // fold safe. A non-AWSD-aware receive fails closed LOUDLY — a skip would silently
                // freeze every ship (D11 stale-kit posture).
                if (onAwsdFrame == null) {
                    throw new IOException("predict datagram SNAP carried an AWSD frame on a"
                            + " non-AWSD-aware receive (the server's cc.net.snapDelta lane"
                            + " requires the AWSD-aware demuxed overload — stale kit?)");
                }
                onAwsdFrame.accept(message.awsdFrame());
                continue;
            }
            if (message.kind() != ServerStreamMessage.Kind.SNAPSHOT) {
                throw new IOException("predict datagram SNAP carried a " + message.kind()
                        + " frame (protocol break — snapshots are the SNAP lane's only payload)");
            }
            ServerSnapshotFrame frame = message.frame();
            if (!passthroughTake || lastSnapshot == null
                    || frame.simTick() >= lastSnapshot.simTick()) {
                // A passthrough take is the ONE tick-regressing return this class
                // makes; lastSnapshot feeds sendIntent's default echoTick → the intent's
                // frameSeq, which must never regress. Every other path stays byte-identical.
                this.lastSnapshot = frame;
            }
            return frame;
        }
    }

    /** Route one in-order CTRL payload (runs OUTSIDE {@code lock} — consumers may block). */
    private void routeCtrl(byte[] payload,
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture,
            Consumer<? super ClientHulkPoseRecord> onHulkPose,
            Consumer<? super ClientHulkCutGeometryRecord> onHulkCutGeometry,
            Consumer<? super ClientRosterAppend> onRosterAppend) throws IOException {
        if (onEdgeEvent == null) {
            // The plain receive's TCP twin reads a bare AWSS stream and fails closed on any
            // interleaved frame; mirror that exactly rather than silently swallowing a boundary.
            throw new IOException("unexpected CTRL frame on the plain (no-demux) datagram receive"
                    + " — the udp lane requires the demuxed overloads (cc.predict.eventReplay)");
        }
        ServerStreamMessage message = decodePayload(payload, "CTRL");
        switch (message.kind()) {
            case GAMEPLAY_EDGE_EVENT:
                onEdgeEvent.accept(message.gameplayEdgeEvent());
                break;
            case MATCH_RESET:
                flipRound(); // The flip is structural — it happens on EVERY drained MRST,
                             // even when the caller's consumer only count-ignores the reset
                ctrlRoundEpoch++; // The ctrl round epoch (ASTP + RAPP + MRST; net thread)
                // The reset carries the epoch it opened (by value).
                onReset.accept(message.matchReset().withRoundEpoch(ctrlRoundEpoch));
                break;
            case DESPAWN:
                onDespawn.accept(message.despawn());
                break;
            case SEAT_NAMES:
                onSeatNames.accept(message.seatNames());
                break;
            case SEAT_NAME_RECORD:
                // The demuxed overloads always pass a consumer here (the non-aware
                // ones a counting no-op) — only the plain no-demux receive reaches routeCtrl
                // with null consumers, and it threw above on ANY CTRL.
                onSeatNameRecord.accept(message.seatNameRecord());
                break;
            case HULK_FRACTURE:
                // Same posture as SEAT_NAME_RECORD — the non-aware overloads pass a
                // counting no-op, so a fracture edge can never kill a lesser-overload read.
                onHulkFracture.accept(message.hulkFracture());
                break;
            case HULK_POSE:
                // Same posture — the non-aware overloads pass a counting no-op,
                // so a corpse-pose keyframe can never kill a lesser-overload read.
                onHulkPose.accept(message.hulkPose());
                break;
            case HULK_CUT_GEOMETRY:
                // Same posture — the non-aware overloads pass a counting no-op,
                // so a cut-geometry record can never kill a lesser-overload read.
                onHulkCutGeometry.accept(message.hulkCutGeometry());
                break;
            case ROSTER_APPEND:
                // Same threading posture (non-aware overloads pass a counting
                // no-op) — but gameplay-critical: the live plugin loop runs the append-aware
                // overload, and a nonzero ignore count marks a caller that must be widened.
                onRosterAppend.accept(message.rosterAppend().withRoundEpoch(ctrlRoundEpoch)); //
                break;
            case SEAT_STATE:
                recordSeatState(message.seatState());
                break;
            case MATCH_OVER:
                // The SEAT_STATE internal-route idiom — latched in-session,
                // the plugin polls matchOverRecord(); no per-consumer overload chain to widen.
                recordMatchOver(message.matchOver());
                break;
            case PHASE_DIVE:
                // Same internal-route idiom — queued in-session, the
                // plugin drain-takes takePhaseDives(); no per-consumer overload chain to widen.
                recordPhaseDive(message.phaseDive());
                break;
            case VARIANT_DEFINITION:
                // Same internal-route idiom — queued in-session, the plugin's
                // fold lane drain-takes takeVariantDefinitions() just ahead of its RAPP fold
                // (catalog ≺ any RAPP row naming a wire-only variant holds because the door
                // bursts VDEF before the newly-live RAPP replay; a lost race degrades to the
                // fold lane's loud SKIP-ENTRY fallback, never corruption).
                recordVariantDefinition(message.variantDefinition());
                break;
            case VARIANT_RECEIPT:
                // W15 v0: same internal-route idiom — queued in-session, the combat
                // receipt lane drain-takes takeVariantReceipts() and toasts the verdict; the
                // server's unicast-to-submitter law means only a session that sent a VSUB ever
                // routes here.
                recordVariantReceipt(message.variantReceipt());
                break;
            case POSTURE_BLOCK:
                // Same internal-route idiom — latched latest-wins in-session,
                // the combat plugin drain-takes takePostureBlockOrNull() and dumps the sidelog
                // posture rows once per latch (the take-clears contract is the idempotence).
                recordPostureBlock(message.postureBlock());
                break;
            case MODULE_MOUNT:
                // Same internal-route idiom — queued in-session,
                // the plugin's mount lane drain-takes takeModuleMounts() BEFORE its RAPP drain
                // (wire order MMNT≺RAPP holds on the strict-FIFO CTRL spool; the render-side
                // full-drain-per-frame preserves it, and a cold-guard frame degrades to the
                // mount lane's pending-retry table, never corruption).
                recordModuleMount(message.moduleMount());
                break;
            case COMMAND_TRANSFER:
                // Same internal-route idiom — queued in-session, the
                // plugin drain-takes takeCommandTransfers() count-only until the L4 own-view
                // pod-riding leg applies them (the take keeps the queue empty/bounded).
                recordCommandTransfer(message.commandTransfer());
                break;
            case PILOT_AIM:
                // Same internal-route idiom — queued in-session, the
                // plugin's remote-aim lane drain-takes takePilotAims() per frame (latest-wins
                // fold there; a cap-dropped frame self-heals at the next ≈8 Hz fan / ≈1 s
                // keepalive, so the drop is presentation lag, never corruption).
                recordPilotAim(message.pilotAims());
                break;
            case ASTEROID_POSE:
                // Same internal-route idiom -- queued
                // in-session with the net-thread round epoch; the plugin's asteroid lane
                // drain-takes takeAsteroidPoses() per frame.
                recordAsteroidPose(message.asteroidPose());
                break;
            case LISTING:
                // Same internal-route idiom — queued in-session, the
                // picker lane drain-takes takeListings() per frame and assembles per-listingId
                // latest-wins (a cap-dropped frame fail-closes that WHOLE listing at assembly;
                // the player re-asks — never corruption, never a partial render).
                recordListing(message.listing());
                break;
            case VOICE_CONFIG:
                // The VCFG voice sheet. Unlike the fail-closed default
                // (an unknown KIND = a contract change) an out-of-domain sheet is a SERVER bug
                // on a known lane: the orchestrator counts + drops it (one log line per
                // session) and the match goes on without voice — never a session kill.
                voice.onVoiceConfigFrame(message.voiceConfigFrame());
                break;
            case RESERVED_CONTROL:
                // Reserved DESC/HOUS kinds are TOLERATED —
                // count-ignored so a future consumer arc never forces a ×N re-kit — EXCEPT the
                // one consumed role: the HOUS KICKED notice (role 1) routes to
                // its first-latch-wins latch (the MATCH_OVER internal-route idiom); the plugin
                // polls kickedNoticeReason(). Everything else stays count-ignored.
                int kickedReason = ClientReservedControlReader.kickedNoticeReasonOrMinusOne(
                        message.reservedControl());
                if (kickedReason >= 0) {
                    recordKickedNotice(kickedReason);
                } else {
                    reservedCtrlIgnored++;
                }
                break;
            case CHAT:
                // The demuxed overloads always pass a consumer here (the non-chat-aware
                // ones a counting no-op) — only the plain no-demux receive reaches routeCtrl with
                // null consumers, and it threw above on ANY CTRL.
                // The editor host card's latch reads first; it CONSUMES only a
                // list line its armed list wait asked for, and such a line reaches no other reader here (not the Fly
                // latch, not onChat = the chat lane's enqueue: the auth replay law's reply read, the help-menu capture,
                // the HUD feed and its length-only log row). A verdict line that settles an ARMED slot is CONSUMED too:
                // the host's chat never shows the editor's own receipts, whose heads carry
                // ids; a verdict with no armed slot and the sign-in lines are latched AND flow on.
                if (!HostShipsLatch.offer(message.chatMessage())) {
                    ShipReplyLatch.offer(message.chatMessage()); // The editor's Fly reply
                    onChat.accept(message.chatMessage());
                }
                break;
            case SNAPSHOT:
                throw new IOException("predict datagram CTRL carried an AWSS snapshot"
                        + " (protocol break — snapshots ride the SNAP channel)");
            default:
                // fail closed: a kind this switch does not know is a contract change, not noise
                throw new IOException("unhandled server stream message kind: " + message.kind());
        }
    }

    /** Decode one datagram payload as exactly one multiplexed server-stream frame — the SAME
     * proven readers the TCP demux uses; a CRC-verified post-admission payload that does not
     * decode is a protocol break (loud), never spoof noise (that died at the envelope).
     * With tick-conflation OFF a CAWZ-wrapped SNAP payload inflates HERE, at the
     * drain — the core stores payloads compressed, so a conflated payload costs zero inflate
     * work; with it ON the unwrap already happened at delivery (the tick peek needs raw
     * bytes) and the wrapper check passes the raw payload through untouched. CTRL payloads
     * never carry the wrapper and pass the cheap magic check untouched either way. */
    private ServerStreamMessage decodePayload(byte[] payload, String laneName)
            throws IOException {
        byte[] raw = payload;
        if (SnapDeflateCodec.isWrapped(payload)) {
            try {
                synchronized (snapInflater) {
                    if (closed) {
                        // inside the monitor: close() ends the inflater under it, so this
                        // check happens-before-orders against the end()
                        throw new IOException("predict datagram session closed mid-decode");
                    }
                    raw = SnapDeflateCodec.unwrapIfWrapped(snapInflater, payload);
                }
            } catch (IOException corrupt) {
                throw new IOException("predict datagram " + laneName + " payload corrupt: "
                        + corrupt, corrupt);
            }
        }
        ServerStreamMessage message;
        try {
            message = MultiplexedServerStreamReader.readMessage(new ByteArrayInputStream(raw));
        } catch (IOException corrupt) {
            // 1290 (81) C5: every reader schema/CRC/field throw (AWSS body, AWSD, GPEE, HKFR)
            // lands HERE and was loud only if the plugin's catcher logged it.
            FallbackAnnounce.announceOnce("snap-decode-throw",
                    "lane=" + laneName + " reason=" + corrupt.getMessage());
            throw new IOException("predict datagram " + laneName + " payload corrupt: " + corrupt,
                    corrupt);
        }
        if (message == null) {
            throw new IOException("predict datagram " + laneName
                    + " payload empty (protocol break)");
        }
        return message;
    }

    /** Round flip at drain time (see the class doc): one increment, promote a matching
     * buffered next-round SNAP into the fresh slot (latest-wins), never flips backwards.
     * Conflation ON promotes the future GROUP wholesale — the fresh slot is reset first even
     * when nothing is buffered (a deliberate deviation from the OFF path's residue-keep):
     * tick spaces do not compare across rounds, so a dying round's high-water or un-taken
     * chunks must never gate the fresh round's feed. */
    private void flipRound() {
        synchronized (lock) {
            currentRoundId = (currentRoundId + 1) & 0xFF;
            freshness.promoteAtRoundFlipLocked(currentRoundId);
        }
    }

    @Override
    public int despawnFramesIgnored() {
        return despawnFramesIgnored;
    }

    @Override
    public int matchResetFramesIgnored() {
        return matchResetFramesIgnored;
    }

    @Override
    public int seatNamesFramesIgnored() {
        return seatNamesFramesIgnored;
    }

    @Override
    public int chatFramesIgnored() {
        return chatFramesIgnored;
    }

    @Override
    public int seatNameRecordFramesIgnored() {
        return seatNameRecordFramesIgnored;
    }

    @Override
    public int hulkFractureFramesIgnored() {
        return hulkFractureFramesIgnored;
    }

    @Override
    public int hulkPoseFramesIgnored() {
        return hulkPoseFramesIgnored;
    }

    @Override
    public int hulkCutGeometryFramesIgnored() {
        return hulkCutGeometryFramesIgnored;
    }

    @Override
    public int rosterAppendFramesIgnored() {
        return rosterAppendFramesIgnored;
    }

    /** Reserved {@code DESC}/{@code HOUS} CTRL frames drained and count-ignored. */
    @Override
    public int reservedCtrlIgnored() {
        return reservedCtrlIgnored;
    }

    /** The ctrl round epoch (volatile read; the seed). */
    @Override
    public int ctrlRoundEpoch() {
        return ctrlRoundEpoch;
    }

    /** Materialize one drained {@code SEAT} frame into the stored table: per-seat
     * newest {@code seatSeq} wins. The durable CTRL lane delivers in order, so the guard only
     * drops replayed/duplicate emissions (full-state frames are idempotent by design). */
    private void recordSeatState(ClientSeatStateEvent event) {
        synchronized (seatStateBySeat) {
            ClientSeatStateEvent existing = seatStateBySeat.get(event.seatId());
            if (existing == null || event.seatSeq() > existing.seatSeq()) {
                seatStateBySeat.put(event.seatId(), event);
                if (shipId > 0L && event.shipId() == shipId) {
                    ownSeatId = event.seatId(); // Our hull's seat (see the field)
                }
            }
            seatStateFramesReceived++;
        }
    }

    /** Snapshot copy of the latest seat-state table (seatId → newest full-state event) —
     * the D4.5 presentation feed. Empty until an N-peer server emits {@code SEAT}. */
    public SortedMap<Integer, ClientSeatStateEvent> seatStateTable() {
        synchronized (seatStateBySeat) {
            return new TreeMap<>(seatStateBySeat);
        }
    }

    /** this session's own seat id, or -1 when unknown — the
     * editor Fly's {@code ShipReplyLatch} keys the server's {@code /ship} reply on it (the reply is unicast
     * stamped with the requester's seat; a peer-headed {@code server} join line carries the JOINER's). */
    @Override
    public int ownSeatIdOrMinusOne() {
        return ownSeatId;
    }

    /** Count of {@code SEAT} frames drained off the CTRL lane (applied or replay-dropped). */
    public int seatStateFramesReceived() {
        synchronized (seatStateBySeat) {
            return seatStateFramesReceived;
        }
    }

    /** Latch one drained HOUS KICKED notice — first latch wins (the burst mails
     * up to three same-seq copies but the core delivers once; a re-fanned notice on a later
     * session generation never reaches this instance). Episode-terminal: cleared only by the
     * deliberate {@link #armReAdmission} re-dial (D4b). */
    private void recordKickedNotice(int reasonCode) {
        synchronized (kickedNoticeLock) {
            if (kickedNoticeReason < 0) {
                kickedNoticeReason = reasonCode;
            }
            kickedNoticeFramesReceived++;
        }
        // D4b: latch + CANCEL under the session lock: a kick can land while
        // a FALLBACK episode is already armed (ctrl_rto_exhausted / receive_failed arm from the
        // timer/reader threads and race the notice drain) — without the cancel, that episode's
        // in-flight re-HELLO auto-re-admits the victim server-side while this latch drops every
        // send (frozen on a live seat — worse than the silent auto-re-admit anti-feature). A HELLO
        // already on the wire may still mint a server session: the adopt refuses it as stale
        // (!reconnecting → welcomeDuplicates), the unfed session dies by udp_silence, and a
        // later deliberate re-entry TAKEOVERs it — bounded, self-healing.
        synchronized (lock) {
            kickedTerminal = true;
            if (reconnecting) {
                reconnecting = false;
                detachHs13FlightsLocked(); // No adopt — the episode's flights retire
                lock.notifyAll();
            }
        }
    }

    @Override
    public int kickedNoticeReason() {
        synchronized (kickedNoticeLock) {
            return kickedNoticeReason;
        }
    }

    /** Latch one drained {@code MOVR} serve-end verdict: first latch wins,
     * mirroring the server's first-latch-wins outcome carry (at most one per serve by the fan
     * contract; a duplicate only bumps the counter). */
    private void recordMatchOver(ClientMatchOverRecord record) {
        synchronized (matchOverLock) {
            if (matchOverRecord == null) {
                matchOverRecord = record;
            }
            matchOverFramesReceived++;
        }
    }

    /** The latched serve-end match verdict, or {@code null} until the server fans {@code MOVR} —
     * the C6 consumer poll surface. */
    /** The session-owned voice orchestrator; never null. */
    @Override
    public VoiceClient voice() {
        return voice;
    }

    @Override
    public ClientMatchOverRecord matchOverRecord() {
        synchronized (matchOverLock) {
            return matchOverRecord;
        }
    }

    /** Count of {@code MOVR} frames drained off the CTRL lane (latched or duplicate-dropped). */
    public int matchOverFramesReceived() {
        synchronized (matchOverLock) {
            return matchOverFramesReceived;
        }
    }

    /** Queue one drained {@code DIVE} phase-dive start: bounded append —
     * past {@link #PHASE_DIVE_QUEUE_CAP} un-taken records the flood drops-with-count (dives are
     * ≤1/round by the vanilla battle-global latch; the cap only bounds a spoofed fan). */
    // BoundedInbox collapse (same cap/count/take law)
    private void recordPhaseDive(ClientPhaseDive record) {
        phaseDiveInbox.offer(record);
    }

    /** Drain-take the queued phase-dive starts — the presentation poll
     * surface; empty (never null) when none arrived since the last take. */
    @Override
    public java.util.List<ClientPhaseDive> takePhaseDives() {
        return phaseDiveInbox.take();
    }

    /** Count of {@code DIVE} frames drained off the CTRL lane (queued or cap-dropped). */
    public int phaseDiveFramesReceived() {
        return phaseDiveInbox.received();
    }

    /** Queue one drained {@code VDEF} variant definition: bounded append —
     * past {@link #VARIANT_DEFINITION_QUEUE_CAP} un-taken records the flood drops-with-count
     * (the once-per-admission burst is ≤ the server's VDEF_BUDGET_TOTAL 960; the cap only
     * bounds a spoofed fan or an un-drained pile-up). */
    // BoundedInbox collapse (same cap/count/take law)
    private void recordVariantDefinition(ClientVariantDefinition record) {
        variantDefinitionInbox.offer(record);
    }

    /** Drain-take the queued variant definitions — the fold-lane poll
     * surface; empty (never null) when none arrived since the last take. */
    @Override
    public java.util.List<ClientVariantDefinition> takeVariantDefinitions() {
        return variantDefinitionInbox.take();
    }

    /**
     * D-FIRSTSHIP: read-only PEEK of the {@code VDEF} definitions on hand BEFORE
     * combat. The door spools the whole catalog to a newcomer at admission (the app-load
     * connect), so by the time the player clicks the mission every wire-only pickable is already
     * parked in {@code ctrlQueue} — un-drained, because only combat's {@link #receiveSnapshot}
     * drains CTRL. Copies the queued payloads under {@code lock}, decodes them OUTSIDE it with
     * the drain's own {@link #decodePayload} (CTRL payloads never carry the deflate wrapper, so
     * the decode is pure — nothing is consumed, the drain re-decodes the same bytes later), and
     * appends the already-drained records. A payload that fails to decode is skipped here — the
     * drain owns the loud protocol-break path; a peek never kills the menu.
     */
    @Override
    public java.util.List<ClientVariantDefinition> peekVariantDefinitions() {
        java.util.List<byte[]> queued;
        synchronized (lock) {
            queued = new java.util.ArrayList<>(ctrlQueue);
        }
        java.util.List<ClientVariantDefinition> peeked = new java.util.ArrayList<>();
        for (byte[] payload : queued) {
            try {
                ServerStreamMessage message = decodePayload(payload, "ctrl-peek");
                if (message != null && message.variantDefinition() != null) {
                    peeked.add(message.variantDefinition());
                }
            } catch (IOException | RuntimeException skip) {
                // the drain owns the loud decode path; a pre-combat peek stays silent
            }
        }
        variantDefinitionInbox.peekInto(peeked); // Same copy, the inbox's monitor
        return peeked;
    }

    /** The LATEST verdict survives the combat lane's drain-take, so the
     *  match-build peek names it instead of "none" once the toast has already fired. */
    private volatile ClientVariantReceipt latestVariantReceipt;

    /** Queue one drained {@code VACK} receipt: the VDEF bounded-append idiom. */
    private void recordVariantReceipt(ClientVariantReceipt receipt) {
        latestVariantReceipt = receipt; // D4: retained across the take
        variantReceiptInbox.offer(receipt); // BoundedInbox collapse
    }

    /** Drain-take the queued {@code VACK} receipts — the combat receipt lane's poll. */
    @Override
    public java.util.List<ClientVariantReceipt> takeVariantReceipts() {
        return variantReceiptInbox.take();
    }

    /** Read-only PEEK of the {@code VACK} receipts on hand: the
     *  {@link #peekVariantDefinitions} shape — queued CTRL payloads decoded OUTSIDE the lock
     *  (pure, nothing consumed) + the already-drained records; a bad payload is skipped here
     *  (the drain owns the loud path). */
    @Override
    public java.util.List<ClientVariantReceipt> peekVariantReceipts() {
        java.util.List<byte[]> queued;
        synchronized (lock) {
            queued = new java.util.ArrayList<>(ctrlQueue);
        }
        java.util.List<ClientVariantReceipt> peeked = new java.util.ArrayList<>();
        for (byte[] payload : queued) {
            try {
                ServerStreamMessage message = decodePayload(payload, "ctrl-peek");
                if (message != null && message.variantReceipt() != null) {
                    peeked.add(message.variantReceipt());
                }
            } catch (IOException | RuntimeException skip) {
                // the drain owns the loud decode path; a pre-combat peek stays silent
            }
        }
        variantReceiptInbox.peekInto(peeked); // Same copy, the inbox's monitor
        if (peeked.isEmpty()) {
            // D4: nothing queued and nothing un-taken — the latest verdict still answers the peek.
            ClientVariantReceipt latest = latestVariantReceipt;
            if (latest != null) {
                peeked.add(latest);
            }
        }
        return peeked;
    }

    /** Count of {@code VACK} frames drained off the CTRL lane (queued or cap-dropped). */
    public int variantReceiptFramesReceived() {
        return variantReceiptInbox.received();
    }

    /** Count of {@code VACK} frames dropped at the queue cap. */
    public int variantReceiptFramesDropped() {
        return variantReceiptInbox.dropped();
    }

    /** Count of {@code VDEF} frames drained off the CTRL lane (queued or cap-dropped). */
    public int variantDefinitionFramesReceived() {
        return variantDefinitionInbox.received();
    }

    /** Count of {@code VDEF} frames dropped at the queue cap (the
     * counter was write-only; the fold lane logs the first drop so a grader has a row to grep). */
    @Override
    public int variantDefinitionFramesDropped() {
        return variantDefinitionInbox.dropped();
    }

    /** Latch one drained {@code PSTB} posture block: latest-wins — the
     * newest admission's server-assigned stamp is the truth (once-per-admission by the server's
     * spool contract; the counter is the flood observable). */
    private void recordPostureBlock(ClientPostureBlock block) {
        synchronized (postureBlockLock) {
            postureBlock = block;
            postureBlockFramesReceived++;
        }
    }

    /** Drain-take the latest {@code PSTB} posture block — the combat
     * plugin's poll surface; {@code null} when none arrived since the last take. */
    @Override
    public ClientPostureBlock takePostureBlockOrNull() {
        synchronized (postureBlockLock) {
            ClientPostureBlock take = postureBlock;
            postureBlock = null;
            return take;
        }
    }

    /** Count of {@code PSTB} frames drained off the CTRL lane (each re-latches latest-wins). */
    public int postureBlockFramesReceived() {
        synchronized (postureBlockLock) {
            return postureBlockFramesReceived;
        }
    }

    /** Queue one drained {@code MMNT} module-mount handover: bounded
     * append — past {@link #MODULE_MOUNT_QUEUE_CAP} un-taken records the flood drops-with-count
     * (mounts are rare human actions plus a ≤seat-count admission replay; the cap only bounds a
     * spoofed fan or an un-drained pile-up). */
    // BoundedInbox collapse (same cap/count/take law)
    private void recordModuleMount(ClientModuleMount record) {
        moduleMountInbox.offer(record);
    }

    /** Drain-take the queued module-mount handovers — the mount-lane poll
     * surface; empty (never null) when none arrived since the last take. */
    @Override
    public java.util.List<ClientModuleMount> takeModuleMounts() {
        return moduleMountInbox.take();
    }

    /** The seat wire ids of the queued, un-taken
     * {@code MMNT} handovers (mount AND dismount — MMNT≺RAPP is kind-agnostic); a PEEK: one copy
     * under the inbox monitor the net thread's {@code offer} takes, at most
     * {@link #MODULE_MOUNT_QUEUE_CAP} records, once per wrap rebuild. Empty (never null) when none. */
    @Override
    public java.util.Set<Long> peekQueuedModuleMountSeats() {
        java.util.List<ClientModuleMount> queued = new java.util.ArrayList<ClientModuleMount>();
        moduleMountInbox.peekInto(queued);
        if (queued.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<Long> seats = new java.util.HashSet<Long>();
        for (ClientModuleMount m : queued) {
            seats.add(Long.valueOf(m.seatWireId()));
        }
        return seats;
    }

    /** Count of {@code MMNT} frames drained off the CTRL lane (queued or cap-dropped). */
    public int moduleMountFramesReceived() {
        return moduleMountInbox.received();
    }

    /** Queue one drained {@code XFER} command-transfer edge: bounded
     * append — past {@link #COMMAND_TRANSFER_QUEUE_CAP} un-taken records the flood
     * drops-with-count (transfers are rare human actions plus a ≤seat-count admission replay;
     * the cap only bounds a spoofed fan or an un-drained pile-up). */
    // BoundedInbox collapse (same cap/count/take law)
    private void recordCommandTransfer(ClientCommandTransfer record) {
        commandTransferInbox.offer(record);
    }

    /** Drain-take the queued command-transfer edges — the plugin's poll
     * surface; empty (never null) when none arrived since the last take. */
    @Override
    public java.util.List<ClientCommandTransfer> takeCommandTransfers() {
        return commandTransferInbox.take();
    }

    /** Count of {@code XFER} frames drained off the CTRL lane (queued or cap-dropped). */
    public int commandTransferFramesReceived() {
        return commandTransferInbox.received();
    }

    /** Consumer-thread append of one decoded {@code LSTD} frame —
     * bounded: past {@link #LISTING_QUEUE_CAP} un-taken frames the flood drops-with-count
     * (listings only follow the seat's own no-arg command, ≤255 frames per drip — D6; the cap
     * only bounds a spoofed fan or an un-drained pile-up, and assembly fail-closes the gap). */
    // BoundedInbox collapse (same cap/count/take law)
    private void recordListing(ClientListingRecord record) {
        listingInbox.offer(record);
    }

    /** Drain-take the queued structured-listing frames — the picker
     * lane's poll surface; empty (never null) when none arrived since the last take. */
    @Override
    public java.util.List<ClientListingRecord> takeListings() {
        return listingInbox.take();
    }

    /** Count of {@code LSTD} frames drained off the CTRL lane (queued or cap-dropped). */
    public int listingFramesReceived() {
        return listingInbox.received();
    }

    /** Count of {@code LSTD} frames dropped at the queue cap. */
    public int listingFramesDropped() {
        return listingInbox.dropped();
    }

    /** Queue one drained {@code AIMP} batched pilot-aim frame:
     * bounded append — past {@link #PILOT_AIM_QUEUE_CAP} un-taken frames the flood
     * drops-with-count (the fan is ≈8 Hz + keepalive; the cap only bounds a spoofed fan or an
     * un-drained pile-up, and the change-gated cadence re-delivers the latest aim anyway). */
    // BoundedInbox collapse (same cap/count/take law)
    private void recordPilotAim(ClientPilotAimFrame frame) {
        pilotAimInbox.offer(frame);
    }

    /** Drain-take the queued pilot-aim frames — the remote-aim
     * lane poll surface; empty (never null) when none arrived since the last take. */
    @Override
    public java.util.List<ClientPilotAimFrame> takePilotAims() {
        return pilotAimInbox.take();
    }

    // BoundedInbox collapse
    /** Queue one drained {@code ASTP} asteroid frame, re-wrapped with the
     * current net-thread {@link #ctrlRoundEpoch}: bounded append -- past
     * {@link #ASTEROID_QUEUE_CAP} un-taken frames the flood drops-with-count. */
    private void recordAsteroidPose(ClientAsteroidFrame frame) {
        ClientAsteroidFrame stamped =
                new ClientAsteroidFrame(frame.simTick(), frame.rows(), ctrlRoundEpoch);
        synchronized (asteroidInbox) { // one monitor for the whole decision, as the old asteroidLock was
            if (asteroidInbox.offer(stamped)) {
                return;
            }
            // The cap drops ALIVE rows only -- a death row is sent
            // once, so its loss was a permanent colliding ghost. Keep the deaths, bounded.
            ClientAsteroidFrame deaths = stamped.deathRowsOnly();
            if (deaths != null) {
                if (asteroidInbox.offerPastCap(deaths, ASTEROID_DEATH_QUEUE_EXTRA)) {
                    asteroidDeathFramesKept++;
                } else {
                    asteroidDeathRowsDropped += deaths.rows().size();
                }
            }
        }
    }

    /** Death-only frames that rode past the queue cap: nonzero = a
     *  render stall dropped ALIVE rows but no ghost was minted. */
    public int asteroidDeathFramesKept() {
        synchronized (asteroidInbox) {
            return asteroidDeathFramesKept;
        }
    }

    /** DESTROYED/EXPIRED rows lost past {@link #ASTEROID_DEATH_QUEUE_EXTRA}: every one
     *  is a possible ghost twin on this seat -- a nonzero flight read is a FINDING. */
    public int asteroidDeathRowsDropped() {
        synchronized (asteroidInbox) {
            return asteroidDeathRowsDropped;
        }
    }

    /** Drain-take the queued asteroid frames -- the asteroid lane's poll
     * surface; empty (never null) when none arrived since the last take. */
    @Override
    public java.util.List<ClientAsteroidFrame> takeAsteroidPoses() {
        return asteroidInbox.take();
    }

    /** Count of {@code ASTP} frames drained off the CTRL lane (queued or cap-dropped). */
    public int asteroidFramesReceived() {
        return asteroidInbox.received();
    }

    /** Count of {@code ASTP} frames dropped at the queue cap. */
    public int asteroidFramesDropped() {
        return asteroidInbox.dropped();
    }

    /** Count of {@code AIMP} frames drained off the CTRL lane (queued or cap-dropped). */
    public int pilotAimFramesReceived() {
        return pilotAimInbox.received();
    }

    // ------------------------------------------------------------------ send (writer thread)

    /**
     * The observer intent period. 500 ms = 2 Hz,
     * 20× inside the server's per-session silence deadline
     * ({@code PersistentPilotIntentCombatServer.SOCKET_LIVENESS_READ_DEADLINE_MILLIS} = 10 000 ms,
     * armed per session by {@code configureLiveness}) and 2× the duplex writer's own 250 ms
     * heartbeat floor; the first intent after admission is never held (join grace). Not a knob
     * (the pt-11 rule: no rate knob unless measurement demands one).
     */
    static final long OBSERVER_INTENT_PERIOD_MILLIS = 500L;
    /** Wall time of the last observer intent that reached the wire; {@code MIN_VALUE} = none yet. */
    private long observerLastIntentSendMillis = Long.MIN_VALUE;
    private long observerIntentsThrottled;

    /** Pure throttle law (a selftest seam): due when nothing was sent yet or the period elapsed. */
    public static boolean observerIntentDue(long lastSendMillis, long nowMillis) {
        return lastSendMillis == Long.MIN_VALUE
                || nowMillis - lastSendMillis >= OBSERVER_INTENT_PERIOD_MILLIS;
    }

    /** Observer intents held back by the 2 Hz throttle since connect (0 for a pilot session). */
    public long observerIntentsThrottled() {
        return observerIntentsThrottled;
    }

    @Override
    public void sendIntent(float cursorFacingDeg, float ownShipFacingDeg, int heldControls,
            boolean fireHeld, float aimWorldX, float aimWorldY, int selectedWeaponGroup,
            int autofireGroupsMask, long targetWireId, int fireEdgeSeq, int activeTubeOrdinal,
            long keyCodes, int mouseButtons, int controlKeys)
            throws IOException {
        sendIntent(cursorFacingDeg, ownShipFacingDeg, heldControls, fireHeld, aimWorldX, aimWorldY,
                selectedWeaponGroup, autofireGroupsMask, targetWireId, fireEdgeSeq, activeTubeOrdinal,
                keyCodes, mouseButtons, controlKeys,
                lastSnapshot != null ? lastSnapshot.simTick() : -1L);
    }

    /** The intent send both pumps reach; {@code keyCodes}/{@code mouseButtons}/{@code controlKeys} (PINT v7) ride
     * into the frame verbatim ({@link PredictSession} states their domains). */
    @Override
    public void sendIntent(float cursorFacingDeg, float ownShipFacingDeg, int heldControls,
            boolean fireHeld, float aimWorldX, float aimWorldY, int selectedWeaponGroup,
            int autofireGroupsMask, long targetWireId, int fireEdgeSeq, int activeTubeOrdinal,
            long keyCodes, int mouseButtons, int controlKeys, long echoTick)
            throws IOException {
        throwIfDeadOrClosed(); // a datagram send cannot fail on a dead peer the way a TCP write
                               // does — surface reader deaths here so the writer loop ends too
        if (reconnecting || ownAuthorityHeld || kickedTerminal) {
            sendsDroppedReconnecting++; // B4: latest-wins lane — the first post-re-admit intent
                                        // supersedes anything dropped here ((55) hold drops count
                                        // here too: same lane, same latest-wins contract; D4b
                                        // kicked-terminal drops ride it as well — the server-side
                                        // session is dead, an intent is spray at a vacated seat)
            return;
        }
        if (observer) {
            // OBSERVER intent THROTTLE. The door
            // drops every observer PINT after paying SNAP delivery + magic + a full decode
            // (PilotIntentDatagramServer.onSnapDelivered) — the stream is a liveness carrier and
            // nothing else, yet the duplex writer fires it per render frame (≈300 pps of waste per
            // observer; the server is 1:N). Gate to OBSERVER_INTENT_PERIOD_MILLIS here — the one
            // seam both the welded (per-snapshot) and duplex (per-frame) pumps cross. Nothing
            // starves: any valid datagram on any channel stamps the server's lastValidArrivalMillis
            // (markArrivalLocked), acks piggyback on every emission and the reliability core
            // flushes a bare ACK when the outbound lane idles, CTRL riders (chat, SNNK) are
            // separate calls behind this one. The plugin's `sent` counter counts CALLS for an
            // observer; the wire count = calls − observerIntentsThrottled().
            long now = nowMillis();
            if (!observerIntentDue(observerLastIntentSendMillis, now)) {
                observerIntentsThrottled++;
                return;
            }
            observerLastIntentSendMillis = now;
        }
        int frameSeq = echoTick >= 0L ? (int) (echoTick & 0x7fffffff) : 0;
        // Live-sampled group: real ordinals AND the DESELECTED sentinel (-2, a wire-valid
        // engine state — selection left the group list; the server mirrors the deselect to release
        // the playerShip autofire override) pass through; only the -1 never-sampled fallback still
        // substitutes the connect-time group so the frame stays wire-valid.
        int group = selectedWeaponGroup >= 0
                || selectedWeaponGroup == PilotIntentFrame.SELECTED_GROUP_DESELECTED
                        ? selectedWeaponGroup
                        : this.selectedWeaponGroup;
        PilotIntentFrame intent = new PilotIntentFrame(
                shipId, frameSeq, cursorFacingDeg, heldControls, fireHeld, group,
                ownShipFacingDeg, aimWorldX, aimWorldY, autofireGroupsMask,
                targetWireId, fireEdgeSeq,
                // -1 (ABSENT) passes through wire-valid like the target lane's
                // sentinels — no connect-time fallback exists for a tube pointer.
                activeTubeOrdinal,
                keyCodes, mouseButtons, controlKeys);
        byte[] bytes = PilotIntentWireCodec.encodeFrame(intent);
        reliability.snapSend(bytes, nowMillis()); // UP = latest-wins SNAP; conflation is the contract
        framesSent++;
    }

    @Override
    public void sendFireDeclaration(int declSeq, long clientTick, float aimFacingDeg,
            float aimWorldX, float aimWorldY, int mountIndex,
            float muzzleX, float muzzleY, float bearingDeg, float speed) throws IOException {
        byte[] bytes = FireDischargeDeclarationWriter.encodeFrame(
                shipId, declSeq, clientTick, aimFacingDeg, aimWorldX, aimWorldY, mountIndex,
                muzzleX, muzzleY, bearingDeg, speed);
        ctrlSendBounded(bytes, "fire-discharge declaration");
    }

    @Override
    public void sendSeatName(String name) throws IOException {
        byte[] frame = SeatNameWriter.encodeFrame(shipId, name);
        lastSeatNameFrame = frame; // Replayed after a re-admit
        ctrlSendBounded(frame, "seat-name announce");
    }

    /** The attach-time CTRL announces (SNAM, PSTE, VSUB) are
     *  per-SESSION facts on the server — a takeover re-HELLO mints a fresh session that has none
     *  of them (the door's SNAM handler expects the re-announce; the posture verdict and the
     *  held pick's VSUB likewise). {@link #adoptWelcomeLocked} swaps in a fresh CTRL core and
     *  clears the queue, so the last frame of each is cached HERE at its send and replayed by
     *  {@link #replayAttachFramesAfterAdopt} outside the lock in BOTH adopt callers. */
    private volatile byte[] lastSeatNameFrame;
    private volatile byte[] lastPostureEchoFrame;
    private volatile byte[] lastVariantSubmitFrame;
    private int attachFramesReplayed; // guarded by lock (counter only)

    /**
     * Replay the cached attach announces on the FRESH core right after an admitted re-HELLO
     * (reader thread, lock NOT held). Same order as the connect lane (SNAM ≺ PSTE ≺ VSUB); the
     * fresh window is empty, so {@link #ctrlSendBounded} never blocks here. A send that fails
     * (session died again mid-replay) is announced once and left to the next episode — the
     * server-side fallbacks (nameless seat, no posture verdict, hold expiry) stay loud.
     */
    void replayAttachFramesAfterAdopt(String path) {
        byte[] snam = lastSeatNameFrame;
        byte[] pste = lastPostureEchoFrame;
        byte[] vsub = lastVariantSubmitFrame;
        int sent = 0;
        try {
            // ctrlSendBounded DROPS silently on the reconnecting / RTO-exhausted paths (it counts
            // sendsDroppedReconnecting instead of throwing) — count only frames that really left.
            if (snam != null) {
                long drops = sendsDroppedReconnecting;
                ctrlSendBounded(snam, "seat-name announce (re-admit replay)");
                sent += sendsDroppedReconnecting == drops ? 1 : 0;
            }
            if (pste != null) {
                long drops = sendsDroppedReconnecting;
                ctrlSendBounded(pste, "posture echo (re-admit replay)");
                sent += sendsDroppedReconnecting == drops ? 1 : 0;
            }
            if (vsub != null) {
                long drops = sendsDroppedReconnecting;
                ctrlSendBounded(vsub, "variant submit (re-admit replay)");
                sent += sendsDroppedReconnecting == drops ? 1 : 0;
            }
        } catch (IOException | RuntimeException replayFailed) {
            FallbackAnnounce.announceOnce("re-admit-replay#ep" + reHelloEpisodes,
                    "attach-frame replay after " + path + " adopt failed at frame " + (sent + 1)
                            + ": " + replayFailed.getMessage()
                            + " (server keeps: no seat name / no posture verdict / held pick expires)");
        }
        synchronized (lock) {
            attachFramesReplayed += sent;
        }
    }

    /** Attach announces re-sent after admitted re-HELLOs; the
     *  plugin's re-admit narration prints it beside {@link #reAdmits()}. */
    @Override
    public long attachFramesReplayed() {
        synchronized (lock) {
            return attachFramesReplayed;
        }
    }

    /** W15 v0: ONE {@code VSUB} on the reliable CTRL lane (the {@code sendSeatName}
     *  idiom; the frame is the VDEF-family envelope with a body-only CRC, never the SNAM shape).
     *  The u16 seq wraps; a body over the budget throws BEFORE any byte is sent. */
    @Override
    public void sendVariantSubmit(ClientVariantDefinition variant) throws IOException {
        sendVariantSubmit(variant, false);
    }

    /** The one VSUB send, with the keep-only bit when
     *  {@code keepOnly}. A keep-only frame is NOT cached for the re-admit replay: that replay re-announces
     *  the seat's HELLO pick, which a keep-only submission (the host's Promote) never changes. */
    @Override
    public void sendVariantSubmit(ClientVariantDefinition variant, boolean keepOnly) throws IOException {
        int seq = variantSubmitSeq.getAndIncrement() & VariantSubmitWriter.SUBMIT_SEQ_MAX;
        // Encoded against the boot id table (a missing table / unknown id = the writer's
        // loud IllegalArgumentException, caught by every submit caller beside the budget refusal).
        byte[] frame = VariantSubmitWriter.encodeFrame(seq, variant,
                cc.starsector.server.runtime.IdTables.installedOrNull(), keepOnly);
        if (!keepOnly) {
            lastVariantSubmitFrame = frame; // D2: a takeover's fresh session holds the HELLO pick anew
        }
        synchronized (keepOnlySeqs) {
            keepOnlySeqs.set(seq, keepOnly); // a plain send on a wrapped seq clears a stale mark
        }
        ctrlSendBounded(frame, "variant submit");
    }

    @Override
    public boolean takeKeepOnlyMark(int submitSeq) {
        if (submitSeq < 0 || submitSeq > VariantSubmitWriter.SUBMIT_SEQ_MAX) {
            return false;
        }
        synchronized (keepOnlySeqs) {
            boolean marked = keepOnlySeqs.get(submitSeq);
            keepOnlySeqs.clear(submitSeq);
            return marked;
        }
    }

    /** The ONE {@code PSTE} posture echo UP at attach (the {@code sendSeatName}
     * shape) — resolves the LIVE client posture off the bundled shared census
     * ({@code ClientPostureRegistry}, sysprop &gt; env-twin &gt; compiled) and reports both raw
     * digests + row/unknown counts; the server verdicts behavior-hash vs its compiled baseline
     * (LOUD row, detection-not-suppression v1). Sent independent of PSTB arrival — the schema-1
     * block is knob-empty, so nothing it carries can move these digests (revisit echo-after-apply
     * when a knob converts to a volatile holder). */
    @Override
    public void sendPostureEcho() throws IOException {
        cc.starsector.server.runtime.ClientPostureRegistry.Posture live =
                cc.starsector.server.runtime.ClientPostureRegistry.resolveLive();
        byte[] frame = PostureEchoWriter.encodeFrame(
                live.rows(), live.unknown(), live.hash(), live.behaviorHash());
        lastPostureEchoFrame = frame; // D5: the takeover session gets its own posture verdict
        ctrlSendBounded(frame, "posture echo");
    }

    /** One {@code CHTU} chat line UP on the reliable CTRL lane (the {@code sendSeatName}
     * shape; the server stamps seat + name and fans the {@code CHTD} relay, echo included). */
    @Override
    public void sendChat(String text) throws IOException {
        ctrlSendBounded(ChatWriter.encodeFrame(text), "chat text");
    }

    /** {@code true} — the dial speaks only version 13 since THE FLIP. */
    @Override
    public boolean handshake13() {
        return true;
    }

    /**
     * Runs on the chat drain's thread. ONE {@code lock} hold reads the pair
     * {@code {auth context, reliability core}} — both adopts swap them in one hold — then, with NO lock held across
     * the map, the two X25519 or the send (the session's lock-order rule, as {@code replayAttachFramesAfterAdopt}):
     * the PRDS-life counter +1, {@link Handshake13Auth#proveOrNull}, and the bounded CTRL send of the proof line on the
     * CAPTURED core only ({@link #ctrlSendPinned}). WHY captured, never "check the generation, then send": the live
     * field can be swapped between a check and the send, and a stale proof must never reach the adopted session (a
     * ledger FAIL on the honest admin). A core retired meanwhile still emits under the id it was minted for
     * ({@link #newReliabilityCore}), so the door drops the frame. Nothing here logs; the secret, root, share, counter
     * and proof never reach a log, an exception or a {@code toString}.
     *
     */
    @Override
    public Auth13Send sendAuth13(String secret) throws IOException {
        Hs13ReHelloFlights.Auth13Context ctx;
        DatagramReliability core;
        synchronized (lock) {
            ctx = hs13.authContextLocked();
            core = reliability;
        }
        if (ctx == null) {
            return Auth13Send.UNAVAILABLE;
        }
        long counter = auth13Counter.incrementAndGet();
        if (counter > Handshake13Auth.COUNTER_MAX) {
            return Auth13Send.UNAVAILABLE;
        }
        String proof = Handshake13Auth.proveOrNull(secret, ctx.root(), ctx.ybAuth(), counter, rng);
        if (proof == null) {
            return Auth13Send.UNAVAILABLE;
        }
        return ctrlSendPinned(core, ChatWriter.encodeFrame(proof)) ? Auth13Send.SENT : Auth13Send.RETIRED;
    }

    /**
     * The pinned-core twin of {@link #ctrlSendBounded}: the same entry gates, bounded stall and
     * {@code ctrl_rto_exhausted} arm, but every {@code ctrlSend} goes to {@code core} and {@code core == reliability}
     * is re-read before each one and at the stall exit. {@code false} = RETIRED: an adopt swapped the core, so the
     * frame is dropped at once (the caller counts it) and {@code armReconnect} is NEVER called — a retired core is never
     * acked again, so its stall says nothing about the live session. {@code true} = enqueued, or dropped exactly as
     * {@link #ctrlSendBounded} drops (reconnecting / kicked / the live core stalled). The check and the enqueue are
     * not atomic, so the core is re-read AFTER a successful enqueue too:
     * an adopt in between = RETIRED. An adopt BEFORE the emission: the frame left under the retired core's dead
     * id, which the door had already re-minted past (its WELCOME is what the adopt read), so the re-proof is the only
     * one the door counts. An adopt between the emission and the re-read: the same, unless this thread stalled there
     * for longer than a round trip — then the door counted the proof on the old session AND counts the re-proof (two
     * at most, the once-law; a wrong secret is charged twice; the delta).
     *
     */
    private boolean ctrlSendPinned(DatagramReliability core, byte[] frame) throws IOException {
        throwIfDeadOrClosed();
        if (reconnecting || kickedTerminal) {
            sendsDroppedReconnecting++;
            return true;
        }
        if (core != reliability) {
            return false;
        }
        long now = nowMillis();
        if (core.ctrlSend(frame, now)) {
            return core == reliability;
        }
        long deadline = now + core.currentRtoMillis();
        while (nowMillis() < deadline && sessionFailure == null && readerFailure == null
                && !closed && !reconnecting) {
            sleepQuietly(TIMER_GRANULARITY_MILLIS);
            if (core != reliability) {
                return false;
            }
            if (core.ctrlSend(frame, nowMillis())) {
                return core == reliability;
            }
        }
        if (core != reliability) {
            return false;
        }
        armReconnect("ctrl_rto_exhausted");
        sendsDroppedReconnecting++;
        return true;
    }

    /** The dial's seed of the {@code /auth} context — {@code PredictDatagramDial.admit}
     * hands the opened {@code Admitted13} on BEFORE the daemons start.
     * */
    void seedAuth13(Handshake13.Admitted13 admitted) {
        synchronized (lock) {
            hs13.setAuthContextLocked(admitted);
        }
    }

    @Override
    public void sendAwsdNack(long entityId, int epoch) throws IOException {
        ctrlSendBounded(AwsdNackWriter.encodeFrame(entityId, epoch), "awsd nack");
    }

    /** One {@code MARK} F9 owner-mark capture UP (the sendAwsdNack shape). */
    @Override
    public void sendOwnerMark(int count, long clientTick) throws IOException {
        ctrlSendBounded(MarkWriter.encodeFrame(count, clientTick), "owner mark");
    }

    /** Bounded-block CTRL send (the endpoint's ctrlSendOrStall mirror). Pre-B4 a stall
     * past one RTO was TERMINAL ({@code ctrl_window_stall}); a stalled window means the server
     * stopped acking — a session death — so B4 arms the re-HELLO and drops the frame instead
     * (exactly-once was a per-session promise; the frame dies with the dead session). */
    private void ctrlSendBounded(byte[] frame, String what) throws IOException {
        throwIfDeadOrClosed();
        if (reconnecting || kickedTerminal) {
            sendsDroppedReconnecting++; // B4/D4b kicked: no server-side session to deliver to
            return;
        }
        long now = nowMillis();
        if (reliability.ctrlSend(frame, now)) {
            return;
        }
        long deadline = now + reliability.currentRtoMillis();
        while (nowMillis() < deadline && sessionFailure == null && readerFailure == null
                && !closed && !reconnecting) {
            sleepQuietly(TIMER_GRANULARITY_MILLIS);
            if (reliability.ctrlSend(frame, nowMillis())) {
                return;
            }
        }
        armReconnect("ctrl_rto_exhausted"); // no-op if a concurrent lane already armed / terminal
        sendsDroppedReconnecting++;
    }

    /** The core's snap-echo RTT — refreshes at ~the server's DOWN cadence (core doc). */
    @Override
    public double netPingMillis() {
        return reliability.snapEchoRttMillis();
    }

    @Override
    public ServerSnapshotFrame lastSnapshot() {
        return lastSnapshot;
    }

    /** The server's WELCOME-echoed bound ship (0 = none; stored only — reserved for the future N>2 ship-adopt lane). */
    public long serverBoundShipId() {
        return serverBoundShipId;
    }

    @Override
    public long shipId() {
        return shipId;
    }

    @Override
    public int selectedWeaponGroup() {
        return selectedWeaponGroup;
    }

    @Override
    public int framesSent() {
        return framesSent;
    }

    /** B4: true while the standing re-HELLO loop is armed (the "reconnecting" presentation
     * state) — receives block, sends drop counted, prediction free-runs. */
    @Override
    public boolean reconnecting() {
        return reconnecting;
    }

    /** Ms since this episode's arm ({@link #armReconnectLocked}), -1 when live.
     * Volatile reads only — the presentation thread never takes {@code lock}. */
    @Override
    public long reconnectingElapsedMillis() {
        if (!reconnecting) {
            return -1L;
        }
        return Math.max(0L, nowMillis() - reconnectArmedAtMillis);
    }

    /** Inbound silence before the current episode's arm, 0 when live. */
    @Override
    public long reconnectingSilentBeforeArmMillis() {
        return reconnecting ? reconnectSilentBeforeArmMillis : 0L;
    }

    /** The current episode is the deliberate re-baseline arm, not a fallback. */
    @Override
    public boolean reconnectingDeliberate() {
        return reconnecting && reconnectDeliberate;
    }

    /** Rows only: the adopted session id — signed, 0 never adopted. */
    long sessionIdForRows() {
        return sessionId;
    }

    /** B4: completed re-admissions on this same session object (fresh WELCOME adopted). */
    @Override
    public long reAdmits() {
        return reAdmits;
    }

    @Override
    public long serverLife() {
        return serverLife;
    }

    /** The admitted WELCOME's {@code u8 serveSchemaMax} ((83) SV-6; 0 until first admission). */
    public int serveSchemaMax() {
        return serveSchemaMax;
    }

    /** The reliability core's counter snapshot for the F9 wire-mark row and the
     * merger-meter flush (null before a core exists; a re-attach re-creates the core, so a
     * consumer's delta across one reads negative — the sign is the witness, never clamped). */
    public DatagramReliability.Stats reliabilityStats() {
        DatagramReliability r = reliability;
        return r == null ? null : r.stats();
    }

    /** B4: death→re-HELLO arm events (each mints one episode nonce). */
    @Override
    public long reHelloEpisodes() {
        return reHelloEpisodes;
    }

    /** B4: standing re-HELLO datagrams sent across all episodes. */
    @Override
    public long reHellosSent() {
        return reHellosSent;
    }

    /** initial-dial HELLO retransmits (wall clock). */
    @Override
    public int connectHelloRetransmits() {
        return connectHelloRetransmits;
    }

    /** initial-dial pre-admission silent drops. */
    @Override
    public int connectPreAdmitDrops() {
        return connectPreAdmitDrops;
    }

    /** initial-dial door COOKIE challenges answered. */
    @Override
    public int connectCookieChallenges() {
        return connectCookieChallenges;
    }

    /** B4 re-HELLO door COOKIE challenges answered. */
    @Override
    public long reHelloCookieChallenges() {
        return reHelloCookieChallenges;
    }

    /** Version-13 re-HELLO {@code HELD}s at the flight's SEAL_CAP (ended line {@code re_hello_held_cap=}). */
    public long reHelloHeldSealCap() {
        return reHelloHeldSealCap;
    }

    /** Version-13 re-HELLO {@code HELD}s by the pair budget (ended line {@code re_hello_held_budget=}). */
    public long reHelloHeldBudget() {
        return reHelloHeldBudget;
    }

    /** r2: CURRENT's seals on a challenge that answered PREVIOUS (ended line {@code re_hello_sibling_seals=}). */
    public long reHelloSiblingSeals() {
        return reHelloSiblingSeals;
    }

    /** The initial version-13 dial's {@code HELD} answers (ended line {@code connect_held=}). */
    public int connectHeld() {
        return connectHeld;
    }

    /** Fresh re-HELLO COOKIEs whose give-back was
     * suppressed (the generation's one give-back already spent). Exported on the plugin's
     * teardown line as {@code re_hello_cookie_givebacks_suppressed=}. */
    @Override
    public long reHelloCookieGiveBacksSuppressed() {
        return reHelloCookieGiveBacksSuppressed;
    }

    /** B4: WELCOMEs dropped by the nonce gate (stale/foreign — not this generation's echo). */
    @Override
    public long welcomeDuplicates() {
        return welcomeDuplicates;
    }

    /** W5-D5: fresh-nonce escalations (un-adopted generations abandoned for a takeover). */
    @Override
    public long reconnectNonceEscalations() {
        return reconnectNonceEscalations;
    }

    /** W5-D5: own mission-exit BYE echoes swallowed while reconnecting —
     * each was one episode the pre-fix code would have silently murdered. */
    @Override
    public long reconnectByeEchoesSwallowed() {
        return reconnectByeEchoesSwallowed;
    }

    /** B4: dead-session stragglers dropped while reconnecting (never fed the core). */
    public long reconnectInboundDropped() {
        return reconnectInboundDropped;
    }

    /** B4: intent/CTRL sends dropped while reconnecting (incl. the arming CTRL stall). */
    public long sendsDroppedReconnecting() {
        return sendsDroppedReconnecting;
    }

    /** The interface twin of {@link #sendsDroppedReconnecting} — the writer loop
     * counts a send only when this did NOT advance across the call. */
    @Override
    public long sendsDropped() {
        return sendsDroppedReconnecting;
    }

    /** re-admissions adopted off the PREVIOUS nonce generation's echo. */
    @Override
    public long prevGenerationAdopts() {
        return prevGenerationAdopts;
    }

    /** Server re-mints of our seat adopted while LIVE (see
     * {@link #adoptLateGenerationLocked}). */
    @Override
    public long lateGenerationAdopts() {
        return lateGenerationAdopts;
    }

    /** Socket recreates (fresh source port) across all episodes. */
    @Override
    public long socketRecreates() {
        return socketRecreates;
    }

    /** Resolved tick-conflation lever (kill-switch knob, default ON). */
    public boolean snapTickConflate() {
        return freshness.tickConflate();
    }

    /** Resolved stale-fold lever (kill-switch knob, default ON; false whenever
     * {@link #snapTickConflate()} is — the composition law). */
    public boolean snapStaleFold() {
        return freshness.staleFold();
    }

    /** current-round SNAP payloads dropped as older than the slot's tick high-water
     * (conflation ON; fresh + future lanes). */
    public long snapOldTickDropped() {
        return freshness.oldTickDropped();
    }

    /** Un-taken SNAP chunks a NEWER tick's arrival discarded (conflation ON;
     * fresh + future lanes) — the catch-up discard itself, distinct from the older-tick refusals. */
    public long snapSupersededDropped() {
        return freshness.supersededDropped();
    }

    /** seq-stale SNAP payloads folded into the fresh tick-group via the stale hook. */
    public long snapStaleFoldQueued() {
        return freshness.staleFoldQueued();
    }

    /** seq-stale SNAP payloads refused by the tick-group as older-tick AND dropped
     * (passthrough OFF; with it ON refusals queue instead — {@link #snapStalePassthroughQueued()}). */
    public long snapStaleOldDropped() {
        return freshness.staleOldDropped();
    }

    /** Resolved stale-passthrough lever (kill-switch knob, default ON; false
     * whenever {@link #snapStaleFold()} is — the composition law). */
    public boolean snapStalePassthrough() {
        return freshness.stalePassthrough();
    }

    /** group-refused older-tick stale chunks queued for passthrough delivery. */
    public long snapStalePassthroughQueued() {
        return freshness.stalePassthroughQueued();
    }

    /** Queued passthrough chunks evicted by the deque cap (drop-oldest) before
     * a take drained them. */
    public long snapStalePassthroughDropped() {
        return freshness.stalePassthroughDropped();
    }

    /** CTRL payloads refused at {@link #CTRL_QUEUE_CAP} (0 = the cap never bit). */
    public int ctrlFramesDropped() {
        return ctrlFramesDropped;
    }

    /** Deepest un-drained CTRL backlog this session — the pre-combat pile-up
     * meter that sizes any future cap change (read it off the teardown receipt row). */
    public int ctrlQueueHighWater() {
        synchronized (lock) {
            return ctrlQueueHighWater;
        }
    }

    /** seq-stale SNAP payloads the core re-routed up the stale hook (0 with the lever
     * OFF — the core then counts them {@code snapStaleDropped} exactly as before). */
    public long snapStaleFolded() {
        return reliability.snapStaleFolded();
    }

    // ------------------------------------------------------------------ reader thread

    private void readLoop() {
        byte[] buf = new byte[RECEIVE_BUFFER_BYTES];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        long lastReturnNanos = 0L; // Reader meter: the previous receive() return
        while (!closed) {
            DatagramSocket s = socket; // per-iteration snapshot (swap-safe)
            if (lastReturnNanos != 0L) {
                long busy = System.nanoTime() - lastReturnNanos; // out-of-receive() time
                if (busy > readerBusyMaxNanos) {
                    readerBusyMaxNanos = busy;
                }
                if (busy > readerBusyWindowMaxNanos.get()) {
                    readerBusyWindowMaxNanos.accumulateAndGet(busy, Math::max); // the since-last-take window max
                }
                if (busy >= 50_000_000L) {
                    readerBusyOver50Ms++;
                }
                snapGap.onReaderBusy(busy); // The local-stall numerator
            }
            try {
                s.receive(packet);
            } catch (IOException receiveFailed) {
                lastReturnNanos = 0L; // a failure/recreate/pacing sleep is not a reader stall
                snapGap.reset(); // Nor a SNAP gap
                if (closed) {
                    return;
                }
                if (s != socket) {
                    continue; // B3: recreateSocketForEscalation closed OUR socket — read the new one
                }
                if (s.isClosed()) {
                    failReader(new IOException(
                            "predict datagram receive failed", receiveFailed));
                    return;
                }
                // B4 (freeze): a receive failure on a LIVE socket is a SESSION death, not
                // a socket death — canonically Windows surfacing a dead server's ICMP
                // port-unreachable as a connection-reset receive (the B5 mortal-server lane).
                // Arm the re-HELLO and keep reading; the pacing sleep stops a hot spin when the
                // failure repeats back-to-back.
                armReconnect("receive_failed: " + receiveFailed.getMessage());
                if (!sleepBestEffort(50L)) {
                    return; // close() interrupts the reader — session lifetime over
                }
                continue;
            }
            long returned = System.nanoTime();
            if (lastReturnNanos != 0L) {
                long gap = returned - lastReturnNanos; // The loop period
                if (gap > readerGapMaxNanos) {
                    readerGapMaxNanos = gap;
                }
                if (gap > readerGapWindowMaxNanos.get()) {
                    readerGapWindowMaxNanos.accumulateAndGet(gap, Math::max); // the since-last-take window max
                }
            }
            lastReturnNanos = returned;
            readerReceives++; // The reader's liveness pulse (escalation row)
            DatagramFrameCodec.Frame frame =
                    DatagramFrameCodec.decode(packet.getData(), packet.getLength());
            if (frame == null) {
                droppedUndecodable++;
                continue;
            }
            SocketAddress source = packet.getSocketAddress();
            if (frame.channel() == DatagramFrameCodec.WireChannel.WELCOME) {
                if (reconnecting) {
                    hs13.onReconnectWelcome(frame, source); // the re-admission (B4)
                    // A re-admission that never faulted
                    // receive() (udp_silence / ctrl_rto_exhausted arms) reaches here with the
                    // pre-blackout SNAP stamp still held — the first post-admit SNAP would clock
                    // the whole episode as a wire stall. Reset HERE (reader thread, the meter's
                    // only writer), never in the lock-held arm/adopt paths on the take thread.
                    snapGap.reset();
                } else {
                    hs13.onLiveWelcome(frame, source); // the B2 late-generation rider
                }
                continue;
            }
            if (frame.channel() == DatagramFrameCodec.WireChannel.HELLO) {
                droppedUnexpectedChannel++; // a client never receives HELLO — spoof/reflection
                continue;
            }
            if (!source.equals(server)) {
                droppedBadSource++;
                continue;
            }
            if (reconnecting) {
                // B4: post-admission traffic for the DEAD session (in-flight stragglers) must
                // not feed the core or refresh liveness — EXCEPT a server BYE for our session,
                // the freeze's match-end exit from the standing loop.
                if (frame.channel() == DatagramFrameCodec.WireChannel.BYE
                        && frame.sessionId() == sessionId) {
                    if (missionExitByeSent) {
                        // W5-D5 root cause: while our mission-exit goodbye is
                        // outstanding, a BYE for the OLD sessionId is OUR OWN echo — the server
                        // already tore that session down at the TAKEOVER, so its match-over
                        // meaning is void. Taking the exit here murdered the re-admission
                        // episode (pump dead ≤250 ms in; flight 3's ended-line was the
                        // witness). Swallow via handleBye() (its latch branch keeps the session
                        // non-defunct) WITHOUT leaving reconnecting; the latch clears at adopt,
                        // so this reading is scoped to exactly the episode window.
                        // Deliberately NO FallbackAnnounce here (diff-review verdict): every fast
                        // re-pick swallows one echo, so this is expected-normal — announce rows
                        // are reserved for degraded seams (zero-rows-green law); the ended-line
                        // counter is the record.
                        reconnectByeEchoesSwallowed++;
                        handleBye();
                    } else {
                        synchronized (lock) {
                            reconnecting = false; // match over — the loop's second exit (freeze)
                            detachHs13FlightsLocked(); // no adopt: the flights retire
                        }
                        handleBye();
                    }
                } else {
                    reconnectInboundDropped++;
                }
                continue;
            }
            if (frame.sessionId() != sessionId) {
                droppedBadSession++;
                continue;
            }
            lastValidArrivalMillis = nowMillis();
            if (frame.channel() == DatagramFrameCodec.WireChannel.BYE) {
                handleBye();
                continue;
            }
            if (frame.channel() == DatagramFrameCodec.WireChannel.VOICE) {
                // VOICE never rides the reliability core (no ack
                // stamping, no seq tracking here) — straight to the orchestrator's decode +
                // bounded queue; a malformed part is count+dropped there, never a kill.
                voice.onVoiceDown(frame.payload());
                continue;
            }
            DatagramReliability.Channel channel = switch (frame.channel()) {
                case SNAP -> DatagramReliability.Channel.SNAP;
                case CTRL -> DatagramReliability.Channel.CTRL;
                case ACK -> DatagramReliability.Channel.ACK;
                default -> null; // unreachable: HELLO/WELCOME/BYE/VOICE handled above
            };
            if (channel != null) {
                if (channel == DatagramReliability.Channel.SNAP) {
                    // The SNAP lane's inter-arrival gap, clocked at the receive()
                    // return stamp of an admitted frame (source + session checked above) and
                    // BEFORE the core's delivery work — decode/apply time lands in the NEXT
                    // gap's busy share, where the local-stall discriminator reads it.
                    snapGap.onSnapReturned(returned);
                }
                // Stash the envelope roundId for the synchronous delivery callbacks (
                // the core is roundId-blind; callbacks run on this thread inside onDatagram).
                readerRoundId = frame.roundId();
                reliability.onDatagram(channel, frame.seq(), frame.ctrlAck(), frame.ctrlAckBits(),
                        frame.snapHiSeen(), nowMillis(),
                        frame.payload().length == 0 ? null : frame.payload());
            }
        }
    }

    /** BYE-ACK = BYE echo: the peer's BYE completes our goodbye; our echo is theirs. */
    private void handleBye() {
        if (byeSent && !byeAcked) {
            byeAcked = true;
        }
        if (missionExitByeSent) {
            // W5-D1: the echo of OUR mission-exit BYE (or a crossed server goodbye for the same
            // already-dead session) must NOT latch peerSaidBye — that latch means session-over
            // and would defunct the kept-open session the B4 re-admit lane still owns. Our
            // already-sent BYE doubles as the echo a crossed server goodbye waits on.
            synchronized (lock) {
                lock.notifyAll();
            }
            return;
        }
        if (!peerSaidBye) {
            peerSaidBye = true;
            sendEnvelopeOnly(DatagramFrameCodec.WireChannel.BYE);
        }
        synchronized (lock) {
            lock.notifyAll(); // wake a blocked take to return its clean-EOF null
        }
    }

    private void failReader(IOException failure) {
        // 1290 (81) C5 mirror: reader-thread deaths (delivery-time header peeks included)
        // surface only via firstFailure() on a later take — announce the reason at the death.
        FallbackAnnounce.announceOnce("reader-death", "reason=" + failure.getMessage());
        readerFailure = failure;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ B4 re-HELLO loop

    /** B4: arm the standing re-HELLO (lock held). ONE {@link Handshake13Flight} per GENERATION
     * ({@link Hs13ReHelloFlights}) — its resends are byte-identical, so a duplicate crossing its WELCOME
     * resolves as the door's idempotent resend, never a takeover of the fresh session. W5-D5:
     * after {@link #RE_HELLO_ESCALATE_AFTER_SENDS} un-adopted sends {@link #pumpReHello}
     * DELIBERATELY builds a fresh flight (a bounded takeover escalation) — idempotency holds within a
     * generation, escalation is the exit from a deaf one. No-op once armed, closed,
     * terminal-failed, or past a match-end BYE (the freeze's exits). The first HELLO goes out on
     * the next timer tick; nothing here latches a failure. */
    private void armReconnectLocked(String fallbackReason) {
        // kickedTerminal: a kicked client never auto-re-HELLOs — the fallback
        // arms (silence/stall/receive-fail) all refuse; armReAdmission clears the flag FIRST,
        // so the deliberate mission re-entry re-dial still arms through here.
        if (reconnecting || closed || peerSaidBye || sessionFailure != null
                || readerFailure != null || kickedTerminal) {
            return;
        }
        reconnectArmedAtMillis = nowMillis(); // Stamp BEFORE the flag flips visible
        // The silence the player already sat through before this arm (the
        // udp_silence deadline for a fallback arm). For a DELIBERATE re-entry re-dial it is the
        // player's own ESC stay (the mission-exit BYE vacates the seat; nothing arrives in the
        // menu — 22 s, not ~0): the banner ignores it on deliberate arms,
        // so the value stays the raw felt silence here.
        reconnectSilentBeforeArmMillis = Math.max(0L, reconnectArmedAtMillis - lastValidArrivalMillis);
        reconnectDeliberate = fallbackReason == null; // A standing fallback episode returned above
        reconnecting = true;
        reHelloEpisodes++;
        episodeEscalations = 0L; // B3 recreate clock
        episodeWelcomesSeen = 0L; // B1 per-episode diagnosis
        if (fallbackReason != null) {
            // 1290 (81) C4/N6: ONE row per blackout episode with the death reason (the arm was
            // counter-only). null = the deliberate armReAdmission re-acquisition, not a fallback.
            FallbackAnnounce.announceOnce("re-hello-episode#" + reHelloEpisodes,
                    "reconnecting armed reason=" + fallbackReason);
        }
        // Seed (once), then every slot flight retires and the
        // timer builds this generation's flight — no flight call here (any thread, lock held).
        hs13.detachAllLocked(dialFlight);
        reconnectGiveBackSpent = false; // A new generation gets its one give-back
        reHelloIntervalMillis = HELLO_RETRY_MILLIS;
        reconnectNonceSends = 0L;
        reconnectEscalateAfterSends = RE_HELLO_ESCALATE_AFTER_SENDS;
        nextReHelloAtMillis = nowMillis();
        lock.notifyAll();
    }

    private void armReconnect(String fallbackReason) {
        synchronized (lock) {
            armReconnectLocked(fallbackReason);
        }
    }

    /** A fresh challenge's once-per-generation give-back (lock held) —
     * the re-HELLO flight's SEND. */
    void giveBackLocked() {
        if (!reconnectGiveBackSpent) {
            // The challenged send REACHED the
            // door, so it is not an un-adopted try — give its generation slot back
            // and restart the doubling at the base cadence, so a lost/held cookied
            // re-HELLO retries at +250 ms, never +500 (NEVER ADD LATENCY).
            // ONCE per nonce generation — a re-challenge
            // per packet (per-packet-rebinding NAT) must not hold the B3 escalation
            // (fresh nonce, then socket recreate) open forever.
            reconnectGiveBackSpent = true;
            reconnectNonceSends = Math.max(0L, reconnectNonceSends - 1L);
            reHelloIntervalMillis = HELLO_RETRY_MILLIS;
        } else {
            // Still answered (the F2 already sent), but the slot and the doubling are kept.
            reHelloCookieGiveBacksSuppressed++;
        }
    }

    /** The re-admission's terminal refusals (lock held), reached from
     * {@link Hs13ReHelloFlights#onReconnectWelcome}: a REJECTED (+ the {@code banned} re-latch: the
     * deliberate re-entry cleared the kicked latch before this re-HELLO), session id 0,
     * a schema refusal — each ends the episode with {@code sessionFailure} latched (first wins
     * nowhere here: the episode was live). {@code true} = refused; the caller returns. */
    boolean refuseReAdmissionLocked(HandshakeCodec.WelcomePayload welcome) {
        IOException refusal = null;
        if (welcome.status() == HandshakeCodec.WELCOME_REJECTED) {
            // 1290 (81) C6 twin: terminal re-admission refusal.
            FallbackAnnounce.announceOnce("welcome-rejected#readmit", "reason="
                    + (welcome.reason().isEmpty() ? "(no reason)" : welcome.reason()));
            if ("banned".equals(welcome.reason())) {
                // Server truth re-latches the notice
                // BEFORE the failure (see the javadoc); the kicked-notice lane's endCombat drives.
                recordKickedNotice(ClientReservedControlReader.KICKED_REASON_BANNED);
                FallbackAnnounce.announceOnce("readmit-banned-relatch#ep" + reHelloEpisodes,
                        "predict readmit REFUSED reason=banned -> kicked notice re-latched"
                                + " (reason=4; the native end drives, no free-run)");
            }
            refusal = new AdmissionRejected("re-admission",
                    welcome.reason().isEmpty() ? "(no reason)" : welcome.reason());
        } else if (welcome.sessionId() == 0L) {
            refusal = new IOException("predict datagram re-admission WELCOME carried"
                    + " session id 0 (protocol break — 0 is the no-session sentinel)");
        } else {
            String schemaRefusal = PredictDatagramDial.schemaCompareAtAdmission(welcome.serveSchemaMax(), "readmit");
            if (schemaRefusal != null) {
                // (83) CL-1 strict twin: terminal like the REJECTED path — the door re-spoke with
                // a below-client ceiling (server replaced mid-match?); retrying it is noise.
                refusal = new IOException("predict datagram re-admission refused by client: "
                        + schemaRefusal);
            }
        }
        if (refusal == null) {
            return false;
        }
        reconnecting = false;
        sessionFailure = refusal;
        detachHs13FlightsLocked(); // no adopt: the flights retire
        lock.notifyAll();
        return true;
    }

    /** Reconnecting ended WITHOUT an adopt (lock held) — the
     * episode's CURRENT + PREVIOUS flights retire (references only; the timer abandons them). */
    private void detachHs13FlightsLocked() {
        hs13.detachPendingLocked();
    }

    /** B4 (freeze): the "session rebuild" half — the adopt tail shared by the re-admission
     * ({@link Hs13ReHelloFlights#onReconnectWelcome}) and the late-generation path
     * ({@link #adoptLateGenerationLocked}) (lock held; the caller validated status/session/schema): rebind the
     * session, swap in a FRESH core, drop the dead session's payloads, refresh liveness. */
    void adoptWelcomeLocked(HandshakeCodec.WelcomePayload welcome) {
        // The connect gate's re-admit twin. An observer
        // re-admitted WITHOUT bit4, or bound to a concrete ship, would seed a pilot seat
        // (AssignedSeatFromWire) under the NO-own-hull mission build; a pilot handed bit4 is the
        // same protocol break. TERMINAL like the connect lane (the door keys re-admit on cid +
        // password mode, so a mismatch is never a transient) — never seeds anything below.
        boolean welcomeObserver = (welcome.matchFlags() & HandshakeCodec.WELCOME_FLAG_SEAT_OBSERVER) != 0;
        if (observer != welcomeObserver || (observer && welcome.boundShipId() != 0L)) {
            String reason = observer != welcomeObserver
                    ? (observer ? "observer_not_granted" : "observer_unrequested")
                    : "observer_bound_nonzero";
            FallbackAnnounce.announceOnce("welcome-observer-mismatch#readmit", "reason=" + reason
                    + " requested=" + observer + " matchFlags=0x" + Integer.toHexString(welcome.matchFlags())
                    + " boundShipId=" + welcome.boundShipId());
            reconnecting = false;
            sessionFailure = new AdmissionRejected("re-admission", reason);
            detachHs13FlightsLocked(); // no adopt: the flights retire
            lock.notifyAll();
            return;
        }
        sessionId = welcome.sessionId();
        serverBoundShipId = welcome.boundShipId();
        serveSchemaMax = welcome.serveSchemaMax();
        serverLife = welcome.serverLife();
        // The connect-lane seed's re-admit twin (idempotent same-match;
        // a rate change across a server restart re-seeds here).
        cc.starsector.client.ServerTickRate.seedFromWire(welcome.tickRate());
        cc.starsector.client.ClientWireKnobs.resetDeliveredCadenceAtAdmit(); // Raw until this admit's PSTB
        // W4-SS: the match-config re-admit twin — a config change across a server
        // restart re-seeds here; mints self-apply it at the next respawn.
        cc.starsector.client.MatchConfigFromWire.seedFromWire(welcome.matchFlags());
        // The custom-policy re-admit twin — a tier change across a server
        // restart re-seeds here; the editor's next 5-s ping snaps the dropdown.
        cc.starsector.client.CustomConfigFromWire.seedFromWire(welcome.customTier(),
                welcome.customPerSeat(), welcome.customSModCap(), welcome.customFlags());
        voice.onWelcomeFlags(welcome.matchFlags()); // W12 L3: voice bit re-seeds too
        // The assigned-seat re-admit twin (idempotent: re-HELLO carries
        // the concrete id, the door returns the cid's own seat).
        cc.starsector.client.AssignedSeatFromWire.seedFromWire(welcome.boundShipId());
        reliability = newReliabilityCore();
        currentRoundId = welcome.startingRoundId();
        ctrlQueue.clear();
        listingInbox.take(); // Drop the dead session's un-taken LSTD frames (BoundedInbox has no clear; take() empties it)
        latestVariantReceipt = null; // D4 peek is per-SESSION: the replayed VSUB's VACK refills it
        freshness.clearForReAdmissionLocked(); // A re-admitted session is a late
                                               // joiner — the dead session's tick
                                               // high-waters must not gate the fresh feed
                                               // (its refusals die with it too)
        lastValidArrivalMillis = nowMillis();
        missionExitByeSent = false; // W5-D1: mission-exit goodbye episode over — BYEs latch again
        reAdmits++;
        reconnecting = false;
        lock.notifyAll();
    }

    /** The late-generation adopt tail (lock held; the caller's test passed), reached from
     * {@link Hs13ReHelloFlights#lateGenerationLocked} (the late-generation rider: the door re-minted our seat after an
     * earlier generation's adopt; the latest mint is the only live session):
     * {@code true} = adopted (the caller replays), {@code false} = the adopt tail refused. */
    boolean adoptLateGenerationLocked(HandshakeCodec.WelcomePayload welcome) {
        lateGenerationAdopts++;
        FallbackAnnounce.announceOnce("re-hello-late-gen#ep" + reHelloEpisodes,
                "late-generation WELCOME adopted (episode " + reHelloEpisodes
                        + "): server re-minted our seat after a previous-generation"
                        + " adopt; following the latest mint");
        adoptWelcomeLocked(welcome);
        return sessionFailure == null;
    }

    /** B4: one standing re-HELLO per due timer tick —
     * {@value #HELLO_RETRY_MILLIS} ms backing off ×2 to the
     * {@value #RE_HELLO_INTERVAL_CAP_MILLIS} ms cap, INDEFINITE until an admitted WELCOME or a
     * match-end BYE. W5-D5: a nonce generation that goes un-adopted escalates to a FRESH nonce
     * (constants doc) — the send interval deliberately does NOT reset at escalation (coarse).
     * Encodes under lock (generation nonce); sends outside it (lock-order rule). */
    private void pumpReHello() {
        boolean recreateSocket = false;
        Hs13ReHelloFlights.Step step = null; // version 13's one action
        java.util.List<Handshake13Flight> retired = null;
        synchronized (lock) {
            if (reconnecting && !closed && nowMillis() >= nextReHelloAtMillis) {
                if (reconnectNonceSends >= reconnectEscalateAfterSends) {
                    // W5-D5: this nonce's WELCOME was never adopted across
                    // a full generation of retries — the deaf-admission class (server session
                    // live, WELCOME/feed path dead). A fresh nonce makes the server resolve the
                    // next HELLO as a same-token TAKEOVER: deaf session torn down, fresh mint.
                    reconnectGiveBackSpent = false; // One give-back per generation
                    reconnectNonceSends = 0L;
                    reconnectEscalateAfterSends = Math.min(reconnectEscalateAfterSends * 2L,
                            RE_HELLO_ESCALATE_SENDS_CAP);
                    reconnectNonceEscalations++;
                    episodeEscalations++;
                    recreateSocket = episodeEscalations
                            % RE_HELLO_SOCKET_RECREATE_EVERY_ESCALATIONS == 0L; // B3
                    // PREVIOUS retires, PREVIOUS <- CURRENT (one prior generation still
                    // listened to), a fresh flight is built; a recreate drops the held unit (bound to the old port)
                    hs13.escalateLocked(recreateSocket);
                    // ONE key per EPISODE (not per escalation): a deaf-but-alive period escalates
                    // every ~16 s indefinitely — per-escalation keys would exhaust the global
                    // 256-key announce budget in ~1 h and silence every OTHER seam's confession
                    // (diff-review find). Running total rides the net-loop `ended` line instead.
                    // The row carries the deaf-episode facts — which silent path
                    // ate the welcomes (none arrived / wrong source / stale nonce), whether the
                    // reader is still pulling datagrams at all, and its thread state + top
                    // frame (a reader parked in a delivery callback shows the same symptoms
                    // as a dead return path; the frame tells them apart).
                    FallbackAnnounce.announceOnce(
                            "re-hello-escalate#ep" + reHelloEpisodes,
                            "fresh-nonce escalation (episode " + reHelloEpisodes
                                    + ", un-adopted after " + reHellosSent
                                    + " total re-HELLOs; next threshold "
                                    + reconnectEscalateAfterSends + " sends)"
                                    + " welcomes_seen=" + episodeWelcomesSeen
                                    + " welcome_duplicates=" + welcomeDuplicates
                                    + " dropped_bad_source=" + droppedBadSource
                                    + " reconnect_inbound_dropped=" + reconnectInboundDropped
                                    + " reader_receives=" + readerReceives
                                    + " last_welcome_source=" + lastWelcomeSource
                                    + " last_welcome_status=" + lastWelcomeStatus
                                    + " local_port=" + socket.getLocalPort()
                                    + " reader=" + readerStateForDiagnosis());
                }
                step = hs13.stepLocked(); // BUILD (pending) | RESEND (CURRENT) | null; NO v12 bytes (none exist)
                accountReHelloSendLocked();
            }
            retired = hs13.drainLocked(); // every tick, reconnecting or LIVE
        }
        Hs13ReHelloFlights.abandonAll(retired); // OUTSIDE lock, before anything is sent
        if (recreateSocket) {
            recreateSocketForEscalation(); // outside lock: takes sendLock (leaf) alone
        }
        if (step != null) {
            hs13.send(step);
        }
    }

    /** B4 cadence + counters of ONE re-HELLO send (lock held): the pump's, and at version 13 the
     * reader's SEND, which it replaces — one helper, no copy. */
    void accountReHelloSendLocked() {
        nextReHelloAtMillis = nowMillis() + reHelloIntervalMillis;
        reHelloIntervalMillis =
                Math.min(reHelloIntervalMillis * 2L, RE_HELLO_INTERVAL_CAP_MILLIS);
        reHellosSent++;
        reconnectNonceSends++;
    }

    /** The recovery lever — a fresh {@link DatagramSocket} (new ephemeral source
     * port ⇒ a fresh NAT/hairpin mapping in both directions) after
     * {@value #RE_HELLO_SOCKET_RECREATE_EVERY_ESCALATIONS} fruitless nonce generations. Under
     * {@code sendLock} so no send straddles the swap; the reader's next receive failure on the
     * closed socket re-reads the field and continues on the new one ({@link #readLoop}). A
     * closed session never swaps (the close() race: its socket.close() must be the last). The
     * server keys admission on the cid, so the new source is an ordinary TAKEOVER to it. */
    private void recreateSocketForEscalation() {
        DatagramSocket fresh;
        try {
            fresh = PredictDatagramDial.newDatagramSocket(); // The re-create twin carries the same SO_RCVBUF
        } catch (IOException openFailed) {
            FallbackAnnounce.announceOnce("re-hello-socket-recreate-failed#ep" + reHelloEpisodes,
                    "socket recreate failed: " + openFailed.getMessage());
            return;
        }
        DatagramSocket old;
        synchronized (sendLock) {
            if (closed) {
                fresh.close();
                return;
            }
            old = socket;
            socket = fresh;
        }
        int oldPort = old.getLocalPort(); // Read BEFORE close() — a closed socket says -1
        old.close(); // unblocks the reader's receive() on the old socket
        socketRecreates++;
        FallbackAnnounce.announceOnce("re-hello-socket-recreate#ep" + reHelloEpisodes,
                "socket recreated (episode " + reHelloEpisodes + ", escalation "
                        + episodeEscalations + "): local port " + oldPort + " -> "
                        + fresh.getLocalPort() + " (fresh NAT/hairpin mapping) rcvbuf="
                        + PredictDatagramDial.effectiveReceiveBufferBytes(fresh));
    }

    /** The reader thread's state + top frame for the escalation row — cheap
     * (once per escalation, a degraded seam) and the one fact that separates "nothing arrived"
     * from "the reader is parked in a callback". */
    private String readerStateForDiagnosis() {
        StackTraceElement[] frames = reader.getStackTrace();
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < frames.length && i < 3; i++) {
            if (i > 0) {
                top.append('<');
            }
            top.append(frames[i].getClassName().substring(frames[i].getClassName().lastIndexOf('.') + 1))
                    .append('.').append(frames[i].getMethodName());
        }
        return reader.getState() + "@" + (top.length() == 0 ? "(no frames)" : top);
    }

    // ------------------------------------------------------------------ reliability callbacks
    // (reader thread, under the core's monitor; may take lock — never the reverse)

    private void onCtrlDelivered(long seq, byte[] payload) {
        boolean firstDrop = false;
        synchronized (lock) {
            if (ctrlQueue.size() >= CTRL_QUEUE_CAP) {
                // Bounded — refuse the newcomer, count, keep the session alive
                // (the reliability core already acked seq; the loss is final by design, as for
                // every sibling cap — a combat-time fan self-heals at its next cadence).
                firstDrop = ctrlFramesDropped++ == 0;
            } else {
                ctrlQueue.addLast(payload);
                if (ctrlQueue.size() > ctrlQueueHighWater) {
                    ctrlQueueHighWater = ctrlQueue.size();
                }
                lock.notifyAll();
            }
        }
        if (firstDrop) {
            // Outside lock (the announce sink logs; lock is never held across a log write).
            // FallbackAnnounce is force-loaded at application load, so this first-fire path is
            // legal under the in-combat SecurityManager too.
            cc.starsector.client.FallbackAnnounce.announceOnce("ctrl-queue-cap",
                    "cap=" + CTRL_QUEUE_CAP + " first_refused_seq=" + seq
                    + " (un-drained CTRL backlog hit the cap; later refusals only count)");
        }
    }

    /** DOWN SNAP behind the round barrier: current round = fresh slot (latest-wins), next
     * round = one-slot future buffer (its MRST is racing retransmission), else stale drop.
     * Conflation ON: the payload unwraps + tick-peeks FIRST (reader thread, outside
     * {@code lock} — the inflater monitor and {@code lock} are taken sequentially, never
     * nested), then the SAME barrier decides the slot and the tick-group rules the insert.
     * A non-current-round payload wastes one µs-scale unwrap on that rare path — accepted;
     * the barrier + insert stay atomic under {@code lock} exactly as before. */
    private void onSnapDelivered(long seq, byte[] payload) {
        int frameRound = readerRoundId;
        if (!freshness.tickConflate()) {
            synchronized (lock) {
                if (freshness.deliverLocked(frameRound, currentRoundId, payload)) {
                    lock.notifyAll();
                }
            }
            return;
        }
        long tick;
        byte[] raw;
        try {
            raw = unwrapAtDelivery(payload);
            tick = peekSimTick(raw);
        } catch (IOException broken) {
            // Loud fail-closed (drain-side posture): a CRC-verified post-admission SNAP that
            // does not unwrap/parse is a protocol break, never spoof noise.
            failReader(new IOException("predict datagram SNAP payload corrupt: " + broken,
                    broken));
            return;
        }
        synchronized (lock) {
            if (freshness.deliverConflatedLocked(frameRound, currentRoundId, raw, tick)) {
                lock.notifyAll();
            }
        }
    }

    /** Stale-fold hook (reader thread, only wired with {@code snapStaleFold} ON): a SNAP
     * payload the core's latest-wins seq gate would have dropped. Envelope round barrier FIRST
     * — only the CURRENT round's chunks are worth folding (a future/other-round stale is drop
     * counted, never buffered: its round's feed re-asserts everything anyway); then the SAME
     * tick-group rules as the fresh lane (in practice only {@code tick ==} the slot high-water
     * appends — the reordered same-tick sibling chunk; older ticks drop counted). */
    private void onSnapStaleDelivered(long seq, byte[] payload) {
        int frameRound = readerRoundId;
        if (frameRound != currentRoundId) { // volatile pre-check; re-checked under lock below
            freshness.noteStaleRoundDropped();
            return;
        }
        long tick;
        byte[] raw;
        try {
            raw = unwrapAtDelivery(payload);
            tick = peekSimTick(raw);
        } catch (IOException broken) {
            failReader(new IOException("predict datagram SNAP payload corrupt: " + broken,
                    broken));
            return;
        }
        synchronized (lock) {
            if (freshness.deliverStaleLocked(frameRound, currentRoundId, raw, tick)) {
                lock.notifyAll(); // a take may be blocked with an empty fresh group
            }
        }
    }

    /** Delivery-side CAWZ unwrap (reader thread; conflation ON only). Same inflater, same
     * monitor as the drain-side site — one thread drives it at a time per mode. */
    private byte[] unwrapAtDelivery(byte[] payload) throws IOException {
        if (!SnapDeflateCodec.isWrapped(payload)) {
            return payload;
        }
        synchronized (snapInflater) {
            if (closed) {
                // inside the monitor: close() ends the inflater under it, so this check
                // happens-before-orders against the end()
                throw new IOException("predict datagram session closed mid-decode");
            }
            return SnapDeflateCodec.unwrapIfWrapped(snapInflater, payload);
        }
    }

    /** Tick peek over one RAW multiplexed AWSS or AWSD frame — the frozen header law
     * ({@code AwssSnapshotReader}: magic(4) + schema(2) + headerBytes(2, validated == 16) +
     * type(4) + bodyBytes(4), body leads simTick(8); {@code AwsdSnapshotReader} lays its
     * 16-B header + leading body simTick out identically) puts the tick
     * at fixed offset {@code HEADER_BYTES}; the append-at-tail schema law keeps it stable.
     * Fail loud on any mismatch — post-CRC non-parse is a protocol break
     * ({@code decodePayload} posture). */
    private static long peekSimTick(byte[] raw) throws IOException {
        if (raw == null || raw.length < AwssSnapshotReader.HEADER_BYTES + 8) {
            throw new IOException("SNAP payload too short for an AWSS/AWSD frame: "
                    + (raw == null ? -1 : raw.length) + " B");
        }
        int magic = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
        int headerBytesExpected;
        if (magic == AwssSnapshotReader.MAGIC) {
            headerBytesExpected = AwssSnapshotReader.HEADER_BYTES;
        } else if (magic == AwsdSnapshotReader.MAGIC) {
            headerBytesExpected = AwsdSnapshotReader.HEADER_BYTES;
        } else {
            throw new IOException("SNAP payload AWSS/AWSD magic mismatch: 0x"
                    + Integer.toHexString(magic));
        }
        int headerBytes = ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF);
        if (headerBytes != headerBytesExpected) {
            throw new IOException("SNAP payload header size mismatch: " + headerBytes);
        }
        long tick = 0L;
        for (int i = 0; i < 8; i++) {
            tick = (tick << 8) | (raw[headerBytesExpected + i] & 0xFF);
        }
        return tick;
    }

    // ------------------------------------------------------------------ outbound

    /** The reliability core's sink: envelope (current round, the session id the emitting core was
     * minted for — {@link #newReliabilityCore}) + send. Called under the core monitor — a datagram
     * send is the allowed blocking; failures are silent (silence is the death signal, and racing
     * close() is benign). */
    private void emitFromCore(DatagramReliability.Emission emission, long coreSessionId) {
        DatagramFrameCodec.WireChannel channel = switch (emission.channel()) {
            case SNAP -> DatagramFrameCodec.WireChannel.SNAP;
            case CTRL -> DatagramFrameCodec.WireChannel.CTRL;
            case ACK -> DatagramFrameCodec.WireChannel.ACK;
        };
        sendRawDatagram(DatagramFrameCodec.encode(channel, currentRoundId, coreSessionId,
                emission.seq(), emission.ctrlAck(), emission.ctrlAckBits(), emission.snapHiSeen(),
                emission.payload()));
    }

    private void sendEnvelopeOnly(DatagramFrameCodec.WireChannel channel) {
        sendRawDatagram(DatagramFrameCodec.encode(channel, currentRoundId, sessionId, 0L, 0L, 0,
                0L, null));
    }

    /**
     * Send one VCUP payload on the VOICE channel from
     * the capture thread. Bypasses the reliability core on purpose ({@code ctrlSendBounded}
     * blocks up to one RTO and stamps the CTRL seq; voice is real-time, loss is PLC's job) —
     * a raw envelope exactly as the server door's {@code Transport.sendVoice} shapes it:
     * {@code (VOICE, currentRoundId, sessionId, voiceSeq, ctrlAck=0, ctrlAckBits=0,
     * snapHiSeen=0, payload)}, the seq ONE monotonic counter owned here so the relay's
     * strictly-advancing check never trips. False = not sent (closed, reconnecting, or a
     * payload the envelope cannot carry — the encoder must never produce one).
     */
    public boolean sendVoice(byte[] vcup) {
        // kickedTerminal (D4b): a kicked player's mic must not keep uplinking into the round
        // they were removed from (the capture thread outlives the kick by the endCombat window);
        // isDefunct: a plain REJECTED / failed session, a peer BYE
        // or a dead reader never sprays VCUP either (closed + sessionFailure + peerSaidBye + readerFailure).
        if (vcup == null || vcup.length == 0 || vcup.length > DatagramFrameCodec.MAX_PAYLOAD_BYTES
                || reconnecting || kickedTerminal || isDefunct()) {
            return false;
        }
        long seq = voiceSeq.incrementAndGet() & 0xFFFF_FFFFL;
        voice.echo().noteSent((int) (seq & 0xFFFFL), System.nanoTime()); // D8 echo rtt: VCDN seq = this seq's low 16
        sendRawDatagram(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.VOICE,
                currentRoundId, sessionId, seq, 0L, 0, 0L, vcup));
        return true;
    }

    void sendRawDatagram(byte[] datagram) {
        try {
            synchronized (sendLock) { // core monitor -> sendLock only; voice takes sendLock alone
                socket.send(new DatagramPacket(datagram, datagram.length, server));
            }
        } catch (IOException sendFailed) {
            // Not a death signal (silence is); racing close() is benign.
        }
    }

    // ------------------------------------------------------------------ timer thread

    /**
     * The reader thread's belt: an unchecked throw out of {@link #readLoop} (the reliability
     * core's delivery callbacks run on this thread) used to kill the thread while
     * {@link #isDefunct()} still read live. It now latches {@code readerFailure} through
     * {@link #failReader} (announced once, waiters woken) and the thread exits, so the session
     * fails CLOSED. Never swallow-and-continue: a dead delivery callback is a dead session. */
    private void readLoopBelted() {
        try {
            readLoop();
        } catch (RuntimeException | Error thrown) {
            failReader(new IOException("predict datagram reader thread died: "
                    + thrown.getClass().getName() + ": " + thrown.getMessage(), thrown));
        }
    }

    /**
     * The timer thread's belt: an unchecked throw out of {@link #timerLoop} (the core's
     * {@code onTimer} retransmit callbacks, the re-HELLO pump) latches {@code sessionFailure}
     * (first failure wins), announces once and exits, so {@link #isDefunct()} fails CLOSED
     * instead of a live-looking session with no retransmits and no re-HELLO.
     * <p>The {@code finally}: this thread's exit — close's
     * interrupt, {@code closed}, or a death — takes every version-13 flight slot, the retired
     * list and the dial's flight ({@link #dialFlight}) under {@code lock} and abandons them
     * OUTSIDE it, never on the frame thread (the flight's CALLER LAW); abandon is idempotent and
     * never throws. Only this thread builds, and no arm passes {@code closed} or a latched
     * failure, so no flight is born after it. */
    private void timerLoopBelted() {
        try {
            timerLoop();
        } catch (RuntimeException | Error thrown) {
            IOException failure = new IOException("predict datagram timer thread died: "
                    + thrown.getClass().getName() + ": " + thrown.getMessage(), thrown);
            FallbackAnnounce.announceOnce("timer-death", "reason=" + failure.getMessage());
            synchronized (lock) {
                if (sessionFailure == null) {
                    sessionFailure = failure;
                }
                lock.notifyAll(); // wake a blocked take to throw the failure
            }
        } finally {
            hs13.timerExit();
        }
    }

    private void timerLoop() {
        hs13.timerFirstPass(); // before any BUILD
        while (!closed) {
            try {
                Thread.sleep(TIMER_GRANULARITY_MILLIS);
            } catch (InterruptedException interrupted) {
                return; // close() interrupts — session lifetime over
            }
            if (reconnecting) {
                pumpReHello(); // B4: the timer paces the standing re-HELLO; the dead session's
                               // core has nothing worth retransmitting (it died server-side)
                continue;
            }
            if (hs13.retirePending()) {
                pumpReHello(); // LIVE, it only retires (its due test reads reconnecting)
            }
            reliability.onTimer(nowMillis());
            if (byeSent && !byeAcked && !peerSaidBye
                    && (missionExitByeDeadlineMillis == 0L
                            || nowMillis() < missionExitByeDeadlineMillis)) {
                // Goodbye resend until the peer's BYE echo — the endpoint mirror. The
                // mission-exit goodbye (W5-D1) is deadline-bounded: past the deadline the server
                // either echoed already or will vacate the seat via its own liveness silence —
                // an unbounded resend would spray a vacated server session forever.
                sendEnvelopeOnly(DatagramFrameCodec.WireChannel.BYE);
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** The B4 arm-refusal set minus the healable {@code reconnecting} (see
     * {@link #armReconnectLocked}) — {@code readerFailure} included: a dead reader thread can
     * never adopt a WELCOME, so that session is beyond B4 healing too. */
    @Override
    public boolean isDefunct() {
        return closed || peerSaidBye || sessionFailure != null || readerFailure != null;
    }

    /** W13: the D4b kicked-terminal latch, for the join click's re-dial decision. */
    @Override
    public boolean isKickedTerminal() {
        return kickedTerminal;
    }

    /** Defunct ⇒ close+{@code true}; live ⇒ keep-open+{@code false} — the
     * app-scoped session stays open across the mission menu (reader/timer/B4 stay armed; the
     * plugin logs, this class stays log-silent). (54): the live branch latches
     * {@code missionExitedKeptOpen} so the next mission init arms the deliberate re-baseline
     * ({@link #armReAdmission}). W5-D1: the live branch ALSO bids goodbye — one BYE
     * now, the timer resends until the peer's echo, deadline-bounded — WITHOUT closing: the seat
     * TRUE-vacates server-side (grace enters, the cautious AI flies the hull) while the socket
     * stays armed for the re-admit. Mid-reconnect skips the BYE (no live server-side session to
     * bid goodbye to — the seat vacated at the death that armed the loop). */
    @Override
    public boolean closeAtMissionExit() {
        if (isDefunct()) {
            close();
            return true;
        }
        missionExitedKeptOpen = true;
        if (!reconnecting && !kickedTerminal) {
            // kickedTerminal (D4b) skips the BYE like mid-reconnect: the seat vacated at the
            // kick, there is no live server-side session to bid goodbye to.
            missionExitByeSent = true;
            missionExitByeDeadlineMillis = nowMillis() + GOODBYE_BOUND_MILLIS;
            byeSent = true;
            sendEnvelopeOnly(DatagramFrameCodec.WireChannel.BYE);
        }
        return false;
    }

    /** The game thread's init-time keymap → {@link Hs13ReHelloFlights#setKeymap} (the holder every re-HELLO builds
     *  from); the predict plugin's init calls it BEFORE {@link #armReAdmission}. Main-written */
    @Override
    public void setPilotKeymap(byte[] keymapOrNull) {
        hs13.setKeymap(keymapOrNull);
    }

    /** Deliberate fresh-nonce re-HELLO at mission re-acquisition of a LIVE
     * kept-open session (consumes the {@link #closeAtMissionExit} latch — no latch ⇒ first-ever
     * mission init ⇒ no-op {@code false}). The fresh episode nonce makes the server resolve
     * admission as a same-token TAKEOVER of the old sessionId, and the fresh sessionId is exactly
     * what re-fires the newly-live catch-up (seat baseline + per-round RAPP replay + seat-name
     * spool): the re-baseline, zero new server code. Also holds UP intents until
     * {@link #resumeOwnAuthority} so the fresh writer's deploy-default facing never becomes
     * server truth (55). Defunct ⇒ no-op (the mission-init defunct gate already refused).
     * Already-reconnecting ⇒ {@link #armReconnectLocked} no-ops and the standing B4 episode's
     * WELCOME re-baselines the same way — the hold still arms, correct either way. */
    @Override
    public boolean armReAdmission() {
        synchronized (lock) {
            if (!missionExitedKeptOpen || isDefunct()) {
                return false;
            }
            missionExitedKeptOpen = false;
            // W5-D1: reset the mission-exit goodbye state for the fresh episode. The
            // missionExitByeSent suppress latch deliberately stays SET until the WELCOME adopt:
            // a late echo of the mission-exit BYE landing mid-re-admission must keep suppressing
            // the peerSaidBye latch or it would defunct the fresh episode.
            byeSent = false;
            byeAcked = false;
            missionExitByeDeadlineMillis = 0L;
            ownAuthorityHeld = true;
            // D4b: the deliberate re-dial is the ONE clear of the kicked latch pair — BEFORE the
            // arm (kickedTerminal is in the arm-refusal set), so a kicked player's mission
            // re-entry rejoins via this exact lane and the fresh mission's poll starts at -1.
            kickedTerminal = false;
            synchronized (kickedNoticeLock) {
                kickedNoticeReason = -1;
            }
            armReconnectLocked(null); // deliberate re-acquisition — not a fallback, no announce
            return true;
        }
    }

    /** Release the {@link #armReAdmission} UP-intent hold — the plugin calls
     * this at its one-shot fresh-world adopt; own intents are client-authoritative again. */
    @Override
    public void resumeOwnAuthority() {
        ownAuthorityHeld = false;
    }

    /** Goodbye then teardown (s16 law): bounded CTRL flush on BOTH paths — even when the SERVER
     * said BYE first — then BYE resent until the peer's echo, then socket close. Idempotent,
     * never throws (the TCP session's close contract; teardown must not fail teardown). */
    @Override
    public void close() {
        synchronized (closeLock) {
            if (closeStarted) {
                return;
            }
            closeStarted = true;
        }
        goodbye();
        closed = true;
        voice.close(); // W12 L3: idempotent; clears the net->audio queue + meters
        synchronized (sendLock) { // Ordered against a concurrent socket recreate
            socket.close(); // unblocks the reader's receive()
        }
        reader.interrupt();
        timer.interrupt();
        synchronized (lock) {
            lock.notifyAll(); // wake any blocked take to observe closed
        }
        synchronized (snapInflater) {
            snapInflater.end(); // ordered against a mid-drain inflate (decodePayload's monitor)
        }
    }

    private void goodbye() {
        if (reconnecting) {
            return; // B4: no live server-side session to flush to or bid goodbye — the seat
                    // vacated at the death that armed the loop; teardown goes straight to close
        }
        long deadline = nowMillis() + GOODBYE_BOUND_MILLIS;
        while (!reliability.ctrlFlushed() && nowMillis() < deadline
                && sessionFailure == null && readerFailure == null) {
            if (!sleepBestEffort(TIMER_GRANULARITY_MILLIS)) {
                return;
            }
        }
        if (peerSaidBye) {
            return; // the reader already echoed our BYE-ACK
        }
        byeSent = true;
        sendEnvelopeOnly(DatagramFrameCodec.WireChannel.BYE);
        while (!byeAcked && !peerSaidBye && nowMillis() < deadline
                && sessionFailure == null && readerFailure == null) {
            if (!sleepBestEffort(TIMER_GRANULARITY_MILLIS)) {
                return; // the timer thread resends the BYE while we wait
            }
        }
    }

    // ------------------------------------------------------------------ small helpers

    private IOException firstFailure() {
        IOException failure = sessionFailure;
        return failure != null ? failure : readerFailure;
    }

    private void throwIfDeadOrClosed() throws IOException {
        IOException dead = firstFailure();
        if (dead != null) {
            throw dead;
        }
        if (closed) {
            throw new IOException("predict datagram session closed");
        }
    }

    private static void sleepQuietly(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("predict datagram wait interrupted", interrupted);
        }
    }

    /** Close-path sleep: never throws; returns false on interrupt (goodbye bails, teardown wins). */
    private static boolean sleepBestEffort(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A reliability core wired for the resolved freshness levers: the stale hook goes in ONLY with
     * {@code snapStaleFold} ON — the bundled core stays knob-free (the composer law),
     * the session passes the hook explicitly. Used at construction and the B4 re-admit swap.
     *
     * <p>The sink closes over the {@link #sessionId} the core
     * is minted under and stamps THAT id on every emission ({@code currentRoundId} stays a live read —
     * it rolls inside one session). WHY: a core retired by an adopt can still emit (a send or a timer
     * tick that read the field just before the swap); stamped with the LIVE id, its frame would reach
     * the NEW server session at the old core's seq — e.g. an {@code /auth} proof minted for the old
     * session (a ledger FAIL). Under its own id the door drops it silently: an id no longer in
     * {@code sessionsById} ({@code droppedBadSession}) or a pending-death session
     * ({@code droppedDeadSession}). Both mints follow the only two {@code sessionId} writes (the
     * constructor; {@link #adoptWelcomeLocked}), so outside that race nothing changes.
     * */
    private DatagramReliability newReliabilityCore() {
        final long mintedFor = sessionId;
        DatagramReliability.Sink sink = emission -> emitFromCore(emission, mintedFor);
        return freshness.staleFold()
                ? new DatagramReliability(DatagramReliability.Config.defaults(),
                        sink, this::onCtrlDelivered, this::onSnapDelivered,
                        this::onSnapStaleDelivered)
                : new DatagramReliability(DatagramReliability.Config.defaults(),
                        sink, this::onCtrlDelivered, this::onSnapDelivered);
    }

    /** kill-switch knob: {@code prop} sysprop, then {@code env} fallback, default ON when
     * unset/blank ({@code System.getProperty}/{@code getenv} are loader-B-safe — the
     * {@code CcClientModPlugin}/{@code ClientParityWalker} precedent). Strict parse mirrors the
     * deflate resolver: an unparseable value THROWS (fail closed at construction —
     * connect()'s fail-closed contract surfaces it), never silently defaults. */
    private static boolean resolveLeverKnob(String prop, String env) {
        String raw = System.getProperty(prop);
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getenv(env);
        }
        if (raw == null || raw.trim().isEmpty()) {
            return true; // default ON
        }
        raw = raw.trim();
        if (raw.equalsIgnoreCase("true") || raw.equals("1")
                || raw.equalsIgnoreCase("yes") || raw.equalsIgnoreCase("on")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false") || raw.equals("0")
                || raw.equalsIgnoreCase("no") || raw.equalsIgnoreCase("off")) {
            return false;
        }
        throw new IllegalArgumentException(
                prop + " must be a boolean (true/false/1/0/yes/no/on/off); got \"" + raw + "\"");
    }

    /** ONE monotonic clock (the reliability core requires a single source; endpoint mirror). */
    static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
