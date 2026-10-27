package cc.starsector.server.runtime;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.ops.PeerText;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.MatchSeatAuthority;
import cc.starsector.server.match.PasswordSeatLedger;
import cc.starsector.server.match.WaveSchedule;
import cc.starsector.server.protocol.HouseControlMessage;
import cc.starsector.server.protocol.SeatStateCause;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The UDP datagram pilot endpoint (sole transport since the TCP
 * lane was deleted in full):
 * ONE {@link DatagramSocket} per bind, a
 * persistent reader thread + retransmit-timer thread that outlive every per-round gate
 * (endpoint-scoped reliability — the census-MISS repair: TCP's kernel keeps delivering/acking with
 * zero app threads on the socket, so the app-level {@link DatagramReliability} core must get the
 * same standing service from these two threads, or the post-{@code gate.shutdown()} MRST flush
 * could never complete), and a HELLO/WELCOME admission door consuming the EXISTING R8 stack
 * ({@link SeatAdmission#authorize} over {@link MatchSeatAuthority} — settled shape:
 * consume IN PLACE, no new {@code *Admission*} type; the handshake payload codec lives here).
 *
 * <p><b>Clean separation (owner directive, historical):</b> this began as a PARALLEL implementation
 * behind the retired {@code PilotEndpoint} seam (since deleted in full) —
 * no shared mutable state (the one shared
 * helper is the pure, stateless NIC pick {@link BindAddressPick}). Bind semantics began as a copy of
 * {@code PilotIntentSocketServer.open(bindHost,bindPort)}: {@code ""} = auto pick (a site-local IPv4
 * only; none = strict refuses, lenient binds loopback), {@code "0.0.0.0"} = wildcard advertising a
 * dialable IPv4 (site-local first, else a public one; port files need a dialable
 * host), {@code setReuseAddress(true)} ONLY for an
 * ephemeral port, bind failure closes + rethrows (fail-closed — a VPN-forwarded port that cannot
 * be bound must not silently serve elsewhere).
 *
 * <p><b>Channel routing:</b> UP SNAP = {@code PINT} latest-wins intent deposit (a
 * blocking take from that one-slot deposit, NOT a socket read — {@link PeerSession#takeDepositedIntentOrNull});
 * UP CTRL = {@code PDCL} (freshness-gated + budget-charged, the {@code UpLaneDemux} semantics
 * re-stated per-datagram) / {@code SNAM} (announce holder) / {@code MRER} (counted + ignored — the
 * composition rule pins udp to duplex, where no reentry gate exists); DOWN CTRL =
 * {@code GPEE}/{@code EDSP}/{@code MRST}/{@code SNMS}; DOWN SNAP = {@code AWSS}. Every payload is
 * exactly one existing codec frame — the datagram supplies the frame boundary, codecs unchanged.
 *
 * <p><b>Posture split:</b> pre-admission and un-sessioned input is spoofable — bad
 * CRC/magic/version, unknown session id, wrong source address (migration NOT honored v1),
 * malformed HELLO: all silent-drop + counter, never a throw. Post-admission violations (unknown
 * frame magic on an authenticated channel, PDCL budget floods) fail the SESSION loud through the
 * graceful {@link PilotPeerDisconnectedException} path — kick kills the session, the endpoint and
 * socket survive, the seat re-admits. Death = silence: no valid in-session datagram (ANY
 * channel, keepalives count) past the armed deadline/grace state machine — the SAME arm/grace
 * values and classification texts as TCP, applied to datagram arrival; the exception message
 * carries the {@code udp_silence} / {@code ctrl_window_stall} reason token the verdict grammar
 * commits to.
 *
 * <p><b>Goodbye:</b> {@link #fanGoodbye()} = bounded CTRL flush then BYE; BYE-ACK = a BYE echo
 * (symmetric — whichever side receives a BYE echoes one back, which both acknowledges the peer's
 * goodbye and, if it was itself saying goodbye, completes its own). The timer resends an unacked
 * BYE at keepalive cadence until acked or closed.
 *
 * <p><b>N-peer door (design frozen):</b> the
 * session state lives in an internal {@link PeerSession} table over ONE socket — single-bind
 * source-demux: every in-session frame carries the session id at envelope offset 8, so demux is a
 * table lookup + source-address check with ZERO wire changes. The ONE door is
 * {@link #openMultiPeer(String, int, MatchSeatAuthority, int)} (the legacy 1-session
 * {@code open()} door + its single-session {@code PilotEndpoint} seam were retired with the TCP
 * lane): authority REQUIRED; unknown-token HELLO = new session
 * ({@link SeatAdmission} unchanged decides); same-token HELLO from ANY source = takeover of THAT
 * session (the client-crash-restart lane: pre-release, re-authorize, fresh session replaces the
 * entry); per-session intent pinning (a deposited {@code PINT} — and a
 * {@code PDCL} declaration — whose {@code shipId} is not the session's authorized wire id is
 * dropped + counted, closing cross-seat spoof at the door; every other UP lane keys on the
 * SESSION's seat/ship, never a wire-carried id). The N
 * serve loop drives {@link #liveSessions()} / {@link #awaitLiveSessions(int, long)}, the
 * per-session {@link PeerSession} handles, and the per-tick {@link #sendTickToSession} /
 * {@link #recordTickDown} DOWN fan (encode-once + L6 ctrl spool). Session teardown
 * (BYE / silence / displacement) fails ONE session and vacates its seat cause-aware; the endpoint
 * and every other session survive; zero live sessions is legal idle.
 * Every seat release goes through the uniform cause-aware
 * {@link MatchSeatAuthority#vacate(String, SeatStateCause)} (legal unarmed, where it degrades to
 * the plain release — zero legacy drift; armed by the N serve, every transition reaches the SEAT
 * event stream).
 *
 * <p>Lock order (deadlock rule): the reliability core's monitor (reader-thread callbacks hold it)
 * may take {@code lock}; nothing holding {@code lock} ever calls into a core.
 */
public final class PilotIntentDatagramServer implements AutoCloseable {

    /** {@link #fanGoodbye()} flush bound when liveness is unarmed (an armed endpoint bounds it by the
     * liveness deadline; an unarmed endpoint still must not wedge serve exit). */
    static final long UNARMED_FLUSH_BOUND_MILLIS = 5_000L;
    /** Timer granularity (a 10–20 ms band). */
    static final long TIMER_GRANULARITY_MILLIS = 15L;
    /** L6 per-session CTRL spool bound — matches the reliability core's
     * default send window ({@link DatagramReliability.Config#defaults}): a full window PLUS a
     * full spool of backlog means the peer has not acked two windows' worth of CTRL —
     * hostile-or-dead, the same verdict the legacy bounded block reaches. Public: the
     * VDEF admission budget ({@code VariantDefinitionFrameCodec.VDEF_BUDGET_TOTAL}) is asserted under
     * window + this by {@code WaveInflateContractRows.budgetRows}. */
    public static final int CTRL_SPOOL_BOUND = 512;
    /** Rung-0 N-door observability cadence: one {@code npeer-ctrl} line per live session. */
    private static final long NPEER_CTRL_LINE_MILLIS = 5_000L;
    /** Receive buffer: above {@link DatagramFrameCodec#MAX_DATAGRAM_BYTES}, which
     * {@link DatagramFrameCodec#decode} enforces — a datagram past it never decodes (a silent
     * {@code dropped_undecodable}, it reaches no channel handler); past these 2 048 B it also
     * truncates here. */
    private static final int RECEIVE_BUFFER_BYTES = 2_048;
    /** Wait slice while blocked in {@link #awaitLiveSessions}/the goodbye fan — bounds how stale
     * a deadline/terminal check can go, not a poll of the network (the reader notifies). */
    private static final long WAIT_SLICE_MILLIS = 25L;
    /** Pending-declaration FIFO bound — mirrors {@code UpLaneDemux.MAX_PENDING}. */
    public static final int MAX_PENDING_DECLARATIONS = PilotIntentSocketServer.UpLaneDemux.MAX_PENDING;
    /** Per-session pending AWSD-NACK set cap (a flood bound — the legal ask
     * space is one id per live ship, so 256 coalesced ids is already ≥ 8× the N=31 world). */
    public static final int MAX_PENDING_AWSD_NACKS = 256;
    /** Chat rate cap (policy, NOT protocol — a drained bucket drops + counts,
     * the session survives): per-session token bucket, burst {@code 4}, refill 1 token/s. An admin seat's
     * bucket is wider: {@link #ADMIN_CHAT_BUCKET_BURST}. */
    public static final int CHAT_BUCKET_BURST = 4;
    public static final long CHAT_TOKEN_REFILL_MILLIS = 1_000L;
    /** An ADMIN seat's chat bucket - burst {@code 8}, refill one token
     * per {@code 500} ms (2/s). WHY: one press on the host's ship card costs TWO chat sends (the list refresh plus
     * the verb), so three quick presses outran the plain 4-burst and the silently dropped line read as a dead
     * server; a client-side wait would add latency, so the server widens the admin bucket instead. Still a bound:
     * an admin flood stays capped and counted. Policy, NOT protocol: no wire field changes, and a client mirroring
     * the plain bucket stays under this one. The door picks the pair per send from the seat's admin bit (the burst
     * cap AND the rate), never as a top-up: a bucket starts with the plain {@link #CHAT_BUCKET_BURST}, and a seat
     * that turns admin later keeps its tokens - it simply refills faster, toward the wider cap, from its next chat. */
    public static final int ADMIN_CHAT_BUCKET_BURST = 8;
    public static final long ADMIN_CHAT_TOKEN_REFILL_MILLIS = 500L;
    /** F9 owner-mark rate law (policy, NOT protocol — an inside-window mark
     * drops + counts, the session survives): ≤1 accepted {@code MARK} per seat per second, so a
     * held key or a masher can't spam the serve log. The client lane mirrors it. */
    public static final long MARK_MIN_INTERVAL_MILLIS = 1_000L;
    // The W15 VSUB rate law + hostile budget live in CustomVariantLane
    // (VSUB_BUCKET_BURST / VSUB_TOKEN_REFILL_MILLIS / VSUB_HOSTILE_BUDGET / SubmitBucket) —
    // carved out of this file; the door holds one SubmitBucket per session.
    /** The {@code heldControls} EDGE bits the ingest OR-latches across each
     * deposit-take window (the edge-bit class, ingest-side dual of the
     * {@link cc.starsector.server.client.PilotIntentDatagramClient#EDGE_BIT_MIN_HOLD_FRAMES}
     * producer rule): a press/vent pulse shorter than one sim tick must survive the one-slot
     * latest-wins conflation. LEVEL bits (movement/retreat/shield/cloak) stay pure latest-wins —
     * stretching those would smear real releases. */
    public static final int EDGE_HELD_LATCH_MASK =
            PilotIntentFrame.USE_SHIP_SYSTEM | PilotIntentFrame.VENT_FLUX;
    /** Stamp sentinel: no explicit tick yet — record through the sink's shared tick field. */
    static final long NO_STAMP = Long.MIN_VALUE;

    private final DatagramSocket socket;
    private final boolean offLoopback;
    private final String host;
    private final int port;
    /** Never null (fail-closed at {@link #openMultiPeer} — no perimeter trust; the null-authority
     * legacy smoke door died with the TCP lane). */
    final MatchSeatAuthority seatAuthority;
    /** Always true since the legacy single-session door was deleted; kept as the
     * {@link #requireMultiPeerDoor} guard's seam. */
    private final boolean multiPeer;
    /** Session-table capacity: the descriptor seat count. */
    private final int maxSessions;
    /** Non-null = the D4.7 armed door: the HELLO {@code fingerprint} must equal this EXACTLY,
     * checked in BOTH {@code handleHello*Locked} BEFORE token authorize (TCP-gate order parity).
     * Null = check off (v1 behavior, byte-identical). */
    private final String requiredDoorFingerprint;
    /** The raw + folded expectations derived from {@link #requiredDoorFingerprint}. */
    final DoorFingerprintExpectations doorExpectations;
    /** The boot id table the VSUB/VCHK decoders resolve against (null = engine-less door). */
    private final IdTables idTables;
    /** The Main-minted boot mod set (null on a set-less rig door) the session fold carries. */
    private final ModSet modSet;
    /** The client-optional set (mods.optional) — probe rows with MARK_MODSET_OPTIONAL, excluded from the fold. */
    private final ModSet optionalModSet;
    /** non-null = the PASSWORD-mode multi door — every HELLO's credential slot must
     * equal this ONE shared match password exactly; seats resolve per client-instance id through
     * {@link #passwordLedger}. Null = token mode (byte-identical). Paired with the ledger —
     * fail-closed at {@link #openMultiPeer} on a half-configured pair ({@code openInternal}
     * enforces the npeer-only rule again, defensively). */
    final String matchPassword;
    /**
     * The ADMIN credential (null = no admin seat this serve; token mode always
     * null). A HELLO presenting THIS value in the credential slot passes the password trio AND
     * mints its session admin (per-seat WELCOME bit3 -> F11/F12 + /kick + /wave). Set after
     * construct, before serve ({@link #setAdminPassword}); volatile, read on the reader thread.
     */
    volatile String adminPassword;
    /** The cid→seat ledger (paired with {@link #matchPassword}); the resolved seat's own
     * descriptor token then drives {@link SeatAdmission#authorize} verbatim, so
     * {@link MatchSeatAuthority} stays the ONE occupancy truth. */
    final PasswordSeatLedger passwordLedger;
    /** The password door's team table beside {@link #passwordLedger} (null on a token door): which side each
     *  seat plays for and each player's remembered team; one per door, so a new serve starts empty. Guarded by
     *  {@link #lock}. */
    final SeatTeamTable seatTeams;
    /** W7 B1b: the {@code /ship} pickable-variant allowlist. Nullable — legacy doors
     * (every overload that doesn't thread one) reply "picking unavailable". */
    final PickableVariants pickableVariants;
    /** The door PROBE's reply cap in LSTD frames (≤ 28 ids; the GUI renders
     * "+N more" from {@code totalAvailable} and takes a typed id past the cap). Public: the
     * drill client twin stops collecting at {@code min(seqCount, PROBE_MAX_FRAMES)}. */
    public static final int PROBE_MAX_FRAMES = 4;
    /** The probe's MOD SET listing cap (8 × 7 = 56 mods; a wider set still
     * advertises its full count via totalAvailable and the join page says "and N more"). The
     * reply stays password-gated (a wrong credential gets the ≤~105 B reject), so the extra
     * amplification is only reachable by a password holder. Client mirror {@code DoorProbe.MAX_MODSET_FRAMES}. */
    public static final int PROBE_MODSET_MAX_FRAMES = 8;
    /** The probe listing's constant listingId (the LSTD codec's floor is 1; a
     * probe has no per-seat latest-wins mint to key on). */
    public static final int PROBE_LISTING_ID = 1;
    /** per-source + global probe reply budget (guarded by {@link #lock}). */
    final DoorSourceBudget probeBudget = DoorSourceBudget.forProbe();
    /** The match document a password-proven PROBE with a stale {@code match=} term is served (null = none: a token
     *  door, every rig door). */
    final ProbeMatchDoc matchDoc;
    /** The document term of the probe budget: asked once per stale-term probe IMMEDIATELY before whichever
     *  {@link #probeBudget} ask that probe reaches, keyed by {@link DoorSourceBudget#sourceKey}; the host's own
     *  exempt addresses skip its per-key gap only (guarded by {@link #lock}). */
    final DoorSourceBudget docBudget = DoorSourceBudget.forMatchDoc();
    /** W15 v3 VCHK: the dry-run's per-KEY gap
     * (100 ms, no global window), asked after the {@link DoorGuessLedger#dryRunOpen} gate and
     * BEFORE the compare (guarded by {@link #lock}). */
    final DoorSourceBudget variantCheckBudget = DoorSourceBudget.forVariantCheck();
    /** The dry-run ANSWER cap (64/s global,
     * 0 ms gap), asked AFTER the compare — only credential holders spend it (guarded by {@link #lock}). */
    final DoorSourceBudget variantAnswerBudget = DoorSourceBudget.forVariantAnswer();
    /** The per-source-IP + global ADMISSION budget (why: {@link DoorSourceBudget}). */
    final DoorSourceBudget admissionBudget = DoorSourceBudget.forAdmission();
    /** The CREDENTIAL-FAILURE budget — bounds ANSWERED wrong guesses (why:
     * {@link DoorSourceBudget#forCredentialFailure}); the in-session
     * {@code /auth} guess is its ONLY spender (the HELLO paths are paced by the guess ledger). */
    final DoorSourceBudget credentialFailBudget = DoorSourceBudget.forCredentialFailure();
    /** The GUESS LEDGER — the compare law of
     * the v12 door (replaces the verified-attempt budget). Asked under {@link #lock} by
     * every cookie-verified HELLO AFTER the idempotent resend and BEFORE fingerprint / probe /
     * credential; a hold is a silent drop the client's 250 ms retransmit re-asks. Per resource:
     * one key ≈ 0.125 compares/s steady (backoff 250 ms → 8 s, no reset on PASS, −1/h decay);
     * never-seen keys share a 16/s window, streak ≥ 3 keys an 8/s window, so ANY number of keys
     * gets ≤ ≈ 74 compares/s; PASS-opened (key, cid) lanes are paced 250 ms and window-free.
     * Cliff: first-time joiners are held while a fresh-key flood fills the 16/s window (why and
     * the rest of the residuals: {@link DoorGuessLedger}). Guarded by {@link #lock}. */
    final DoorGuessLedger guessLedger = DoorGuessLedger.forDoor();
    /** The P27 ban set the HELLO seam asks (its own monitor;
     * the boot set arrives via {@link PidsOpenParams#bootBans}, installed before the reader starts;
     * a rig may re-install via {@link #installBans}); the chat-origin (ADMIN) budget
     * against {@link #ADMIN_BAN_CAP} lives in {@link #sourceHold} (charged under {@link #lock}). */
    volatile BanSet bans = new BanSet();
    /** Chat {@code /ban} UNITS per serve — a hold + its paired entries = ONE
     *  unit (the shared admin secret is not an unbounded purge). */
    public static final int ADMIN_BAN_CAP = 16;
    /** The source hold + the ONE budget (A2/A3; dies with the door). */
    final DoorSourceHold sourceHold;
    /** The per-address LIVE-SEAT allowance, bound to this box's addresses at open ({@link SourceSeatCap#boundTo});
     *  read under {@link #lock} at the password door's join branch only. */
    final SourceSeatCap sourceSeatCap;
    /** The operator's armed admin seat keys ({@link BanSet#cidKey} form, insertion order, at most
     *  {@link #ARMED_ADMIN_CAP}): a password-door pilot HELLO whose cid key is here is admitted AS ADMIN and is
     *  neither counted nor capped by {@link #sourceSeatCap}. Written by {@link #armAdminCid} on the SIM thread,
     *  read by the admission path on the reader thread, both under {@link #lock}. Lifetime = this door = the
     *  serve life: nothing persists it, so a relaunched serve admits the host plain until {@code /auth}.
     * */
    private final java.util.Set<String> armedAdminKeys = new java.util.LinkedHashSet<String>();
    /** The most seat keys {@link #armAdminCid} holds per serve (one key per operator window); a further key is
     *  refused ({@code refused_cap}), never evicts an earlier one. */
    public static final int ARMED_ADMIN_CAP = 8;
    /** Every {@code pilot-datagram-admission
     * REJECT} print rides this sampler (≤ 1 line/s/reason; {@link #printRejectSampledLocked}) —
     * the REPLY is never sampled. Guarded by {@link #lock}. */
    private final DoorLogSampler rejectLogSampler = DoorLogSampler.forDoor();
    /** The per-serve stateless return-routability
     * cookie (self-synchronized; verify/mint run on the reader OUTSIDE {@link #lock}, like the
     * ladder classify). Its bytes and key are never logged. */
    final DoorCookie doorCookie = DoorCookie.fresh();
    /** W15 v0 step 3(c): the pick surface — the RAW fresh-claim
     * request map, the server-owned joiner default, the HELLO-pick apply and
     * the own-custom fallback + 30 s hold — carved to {@link SeatVariantPicks}
     * (its own monitor; the door lock is never taken inside it). */
    final SeatVariantPicks variantPicks;
    /** W7c B2: the pre-encoded VDEF admission burst. Nullable — legacy doors and
     * smoke doors emit nothing; the armed-door gate lives in the spool. */
    private final VariantCatalogStore variantCatalog;
    /** W2b E3: the module-seats claim arbiter. Nullable — legacy
     * doors and non-fortress serves reply "unavailable" (the pickableVariants pattern); its
     * tables live under ITS OWN monitor, never this lock. */
    final SeatRebindArbiter moduleSeats;
    /** The reliable-CTRL DOWN fan-out (skeleton + 12 per-kind fans). */
    final CtrlFanout fans;
    /** W3 L1b-ii: MODULE_* dispatch arming — separate from arbiter presence
     * (the arbiter mints on every serve; module verbs stay fortress-gated). */
    final boolean moduleVerbsArmed;
    /** W3: the serve-published {@code /transfer} census — null when the
     * {@code transfer.enabled} knob is off (dispatch honestly "unavailable"). The sim thread
     * publishes into it; dispatch reads under ITS monitor (never this door's lock). */
    final TransferTargets transferTargets;
    /** W2b E4: truly-vacated seats (BYE/silence/kick — never displacement), guarded by
     * {@code lock}, drained via {@link #takeTrueSeatVacates()}. */
    private final List<Integer> trueSeatVacates = new ArrayList<Integer>();
    /** seatId → did the seat's last TRUE vacate come from a two-way session
     * (guarded by {@code lock}; read by {@link #lastVacateReachedTwoWay}). */
    private final java.util.Map<Integer, Boolean> lastTrueVacateTwoWayBySeat =
            new HashMap<Integer, Boolean>();
    /** The join line is armed by the serve loop ({@link #setJoinAnnounce}). */
    private volatile boolean joinAnnounce;
    private final Thread reader;
    private final Thread timer;
    private final SecureRandom random = new SecureRandom();
    /** L-R1: the per-BOOT life nonce every v6 WELCOME carries (u64 tail) — the
     * client's fresh-server-life detector at re-admit. Minted HERE (field, after {@code random})
     * because the door is 1/boot on every serve lane — no composition-root plumbing owed; never
     * 0 (the v6 decoder rejects 0; 0 is the client's unadopted sentinel). */
    private final long serverLife = mintServerLifeNonce();

    /** Guards the session tables + every session's mutable (non-identity) state. Never call into
     * a reliability core while holding this (lock-order rule, class doc). */
    final Object lock = new Object();

    // ---- session tables (guarded by lock) ----
    // Iteration order = admission order (LinkedHashMap).
    final LinkedHashMap<Long, PeerSession> sessionsById = new LinkedHashMap<Long, PeerSession>();
    // HELLO lookup is by token (the takeover/reconnect key). The design's source-addr index is
    // deliberately NOT built: every in-session frame carries the session id (the demux key) and no
    // consumer needs source->session (a recorded deviation from the design).
    private final HashMap<String, PeerSession> sessionsByToken = new HashMap<String, PeerSession>();
    /**
     * The personal-mute carry keyed by the
     * dropped session's TABLE key ({@link #sessionTableKeyLocked}: the cid in password mode, the
     * seat token in token mode — the key the door already trusts as its takeover key; a token-mode
     * cid is unauthenticated, P-B9 A6, so it never keys a carry), independent of the dead
     * {@link PeerSession} object. {@link #removeSessionLocked} and the ban stash both go through
     * {@link #stashMuteCarryLocked}: a non-null {@link VoiceRelay#detachMutes} carry, or — when the detach found no rows
     * but another stash still names the id — a {@link VoiceRelay.MuteCarry#tombstone} that only remaps (
     * PDES B-VAC-M5/M6); a later drop of the same key REPLACES it;
     * {@link #admitLocked} consumes it ONCE (remove, never get) for a same-key HELLO that no
     * predecessor carry served, and a predecessor carry drops the key's entry so one carry never
     * attaches twice. Why: a ban drops the session from {@link #sessionsByToken}, so the post-lift
     * HELLO is FRESH (no predecessor) and every personal mute of — and by — the banned player was
     * lost across ban → lift → rejoin. Bounded: {@link #MUTE_CARRY_KEY_CAP} keys (a wire-derived
     * key; twice the seat table plus the observers, so a FULL table leaving and rejoining once keeps
     * every carry), insertion order = eviction order (oldest out). A carry's raw ids follow a re-mint
     * ({@link #remapCarriedMutesLocked} in {@link #admitLocked}); a muter the door no longer holds is skipped, never resurrected. Guarded by
     * {@link #lock}; the relay calls nest relay-inside-lock exactly as the takeover carry does. The
     * key is never logged (a cid or a seat secret). No clear site: the session tables are never
     * cleared (per-session removal only), so the map dies with the door.
     */
    static final int MUTE_CARRY_KEY_CAP = 2 * (MatchDescriptors.MAX_SEATS + PilotIntentDatagramServer.MAX_OBSERVER_SESSIONS);
    private final LinkedHashMap<String, VoiceRelay.MuteCarry> carriedMutesByKey =
            new LinkedHashMap<String, VoiceRelay.MuteCarry>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, VoiceRelay.MuteCarry> eldest) {
                    return size() > MUTE_CARRY_KEY_CAP;
                }
            };
    /** Carries attached through {@link #carriedMutesByKey} (the same-key predecessor lane never counts). */
    private long muteCarriesByKey;

    public int carriedMutesByKeySize() {
        synchronized (lock) {
            return carriedMutesByKey.size();
        }
    }

    public long muteCarriesByKey() {
        synchronized (lock) {
            return muteCarriesByKey;
        }
    }
    // W7 B1a/B1b chat-command dispatch + /ship override state — carved to its own collaborator
    // (deferred-refactors PIDS seam; tables live under ITS monitor, not this lock).
    final ChatCommandDispatch chatCommands = new ChatCommandDispatch();
    // The chat-verb transport (onChatUp + /kick /ban /switchteams) — takes this door's lock
    // (door.lock); reader thread, except the sim thread's chat god-mode gate predicate.
    final PidsChatVerbs chatVerbs = new PidsChatVerbs(this);
    /** The session-metadata UP frames PSTE / SNNK / MARK, carved to {@link PidsMetaFrames}; reader thread, this lock. */
    final PidsMetaFrames metaFrames = new PidsMetaFrames(this);
    /** The custom-variant UP frame VSUB, carved to {@link PidsVariantSubmit}; reader thread, this lock. */
    final PidsVariantSubmit variantSubmit = new PidsVariantSubmit(this);
    /** The custom-variant PRE-ADMISSION dry-run VCHK, carved to {@link PidsVariantCheck}; reader thread, this lock. */
    final PidsVariantCheck variantCheck = new PidsVariantCheck(this);
    /** The replay door's record-driven session verbs, carved to {@link PidsReplayDoor}; serve thread, this lock (the tenure-gate clear excepted). */
    final PidsReplayDoor replayDoor = new PidsReplayDoor(this);
    /** Admin {@code /wave} verbs parked for the serve loop's operator drain
     * ({@link #drainAdminCommands}); own monitor, never this lock. */
    private final AdminWaveQueue adminWaves = new AdminWaveQueue();
    /** The {@code /debugships} FULL-registry catalogue — doubles as the
     * {@code /spawnfoe} allowlist (an id must canonicalize HERE before it may ride toward the
     * engine; an unresolvable id at spawn would be serve-fatal). Null = debug picking honestly
     * unavailable (contract/legacy/game-free serves). Set after construct, before serve
     * ({@link #setDebugVariants} — the {@link #setAdminPassword} precedent); volatile,
     * read on the reader thread. */
    volatile PickableVariants debugVariants;
    /** W3 L1b: serve-tick heartbeat for dispatch-time floor math ({@link #noteSimTick}). */
    volatile long approxSimTick;

    volatile int roundId; // stamps every outgoing datagram; increments at MRST emission
    /** The per-match sim tick rate every v3 WELCOME carries (u16 tail) — the
     * serve loop threads its {@code effectiveTickRate} in right after opening the door (the door
     * has no fixedDt reference of its own). Volatile: admission runs on the reader thread.
     * Default 60 = the server's own default rate, so codec smokes without a serve loop still
     * mint decodable WELCOMEs. */
    private volatile int welcomeTickRate = 60;
    /** The {@code u8 matchFlags} every v5 WELCOME carries
     * (bit0 = officerSkillsAll, bit1 = clientDebug) — threaded in by the
     * composition root exactly like the tick rate above; NEVER resolved inside the codec (the
     * payload records ride the client jar, platform-pure). Default 0 = every flag OFF, the
     * certified baseline. */
    private volatile int welcomeMatchFlags;
    /** W15: the v11 WELCOME custom-variant tail — threaded in by the composition
     * root via {@link #setWelcomeCustomConfig} exactly like the flags above (NEVER resolved
     * here; the codec rides the client jar). Defaults = {@code CustomVariantConfig}'s own
     * defaults (STANDARD / 4 per seat / engine s-mod cap / submissions OFF), so codec smokes
     * without a serve loop still mint decodable WELCOMEs that say "no submissions". */
    private volatile int welcomeCustomTier = HandshakeCodec.CUSTOM_TIER_STANDARD;
    private volatile int welcomeCustomPerSeat = 4;
    private volatile int welcomeCustomSModCap = HandshakeCodec.CUSTOM_S_MOD_CAP_ENGINE;
    private volatile int welcomeCustomFlags;
    /** Sends swallowed by {@link #sendRawDatagram} — the count that makes
     * {@link #emitFromCore}'s "counted, not thrown" contract true. Atomic, NOT under {@link #lock}:
     * sends run under the reliability cores' monitors (lock-order rule, class doc). */
    private final AtomicLong sendFailures = new AtomicLong();
    /** REPLAY mode — this door re-simulates a recorded match and has no
     *  peers. Its reader never starts and {@link #acceptHs13} throws (admission is impossible), its sessions are
     *  minted from the record by {@link #replayMint} and ended by {@link #replayDie}, and nothing it sends leaves
     *  the process ({@link #sendRawDatagram} counts into {@link #suppressedSends} instead). Opened only by
     *  {@link #openReplay}, on loopback-ephemeral. */
    private final boolean replay;
    /** Datagrams a replay door swallowed at {@link #sendRawDatagram} (always 0 on a live door) — the belt's
     *  meter: a frame that got past the per-session suppressions above the reliability core lands here, so a
     *  re-sim run can say how much traffic it would have sent. Atomic for the {@link #sendFailures} reason. */
    private final AtomicLong suppressedSends = new AtomicLong();
    /** Bandwidth meters — bytes handed to the socket / bytes arrived, endpoint
     * lifetime (per-seat twins live on {@link PeerSession}). Sent is Atomic for the same
     * lock-order reason as {@link #sendFailures}; received is a volatile
     * {@link PidsReaderCounters} field. */
    private final AtomicLong bytesSentTotal = new AtomicLong();
    volatile boolean closed;
    private IOException readerFailure;
    private long nextNpeerCtrlLineAtMillis; // timer thread only (the rung-0 cadence)
    private long lastDoorActivityEmitted; // timer thread only (W5-D5 door-line change detector)

    // ---- gate-fed routing state (endpoint-level; the sink survives re-admission by design) ----
    private volatile WireRecordSink sink;

    // ---- counters (endpoint-level; reliability-core counters live in each core's Stats) ----
    /** Carve: the reader-side volatile counters (their getters read lock-free). */
    final PidsReaderCounters readerCounters = new PidsReaderCounters();
    /** Carve: the door counters — every access under {@link #lock}. */
    final PidsDoorCounters counters = new PidsDoorCounters(); // guarded by lock
    /**
     * The OBSERVER session cap — the number of concurrently LIVE
     * observer sessions the password door admits (the next OBSERVER HELLO REJECTs
     * {@code observer_full}). A javadoc'd constant, not a knob: the only observers today are
     * the owner's second window + the ownerless filmstrip leg (two), and an observer costs the
     * full DOWN fan-out (world snapshots at tick rate) with no seat behind it — a public
     * server's bandwidth ceiling is seats, so the cap stays at the proven need. A live
     * observer holds no ledger claim and no table slot against {@code maxSessions}.
     */
    public static final int MAX_OBSERVER_SESSIONS = 2;
    /** The chat verbs an observer cannot run — every verb that acts on the sender's own seat/hull (seat 0
     * has neither) + the admin {@code /debugships} pair: its store keys the sender's seat, and seat 0 is
     * nobody's. Admin verbs stay admin-checked; /mute, /auth, /help + plain chat ride. */
    public static final java.util.Set<ChatCommands.Kind> OBSERVER_BOUNCED_VERBS = java.util.Collections.unmodifiableSet( // public ⇒ immutable
            java.util.EnumSet.of(ChatCommands.Kind.SUICIDE, ChatCommands.Kind.SHIP_LIST,
                    ChatCommands.Kind.SHIP_SET, ChatCommands.Kind.MODULE_LIST,
                    ChatCommands.Kind.MODULE_SET, ChatCommands.Kind.MODULE_OFF,
                    ChatCommands.Kind.TRANSFER_LIST, ChatCommands.Kind.TRANSFER_SET,
                    ChatCommands.Kind.TRANSFER_WIRE, ChatCommands.Kind.PODTEST, ChatCommands.Kind.DEBUGSHIPS_SET,
                    ChatCommands.Kind.GODMODE, ChatCommands.Kind.TELEPORT, ChatCommands.Kind.DEBUGSHIPS_LIST,
                    // Peer-build sharing v2: the share verbs act on the sender's OWN accepted
                    // customs / fielding flag — an observer has neither (/builds, a read, stays).
                    ChatCommands.Kind.SHARE_LIST, ChatCommands.Kind.SHARE_SET,
                    ChatCommands.Kind.SHARE_SET_SILENT, ChatCommands.Kind.SHARE_AUTO,
                    ChatCommands.Kind.SHARE_AUTO_SILENT, ChatCommands.Kind.UNSHARE_SET,
                    // /team names the sender's own seat's team
                    ChatCommands.Kind.TEAM_SHOW, ChatCommands.Kind.TEAM_SET));
    /** The one bounce reply for {@link #OBSERVER_BOUNCED_VERBS}. */
    static final String OBSERVER_VERB_BOUNCE = "observer: seat verbs unavailable";
    /** wq P25: the squat AGE FLOOR — a never-two-way claim younger than this is
     *  not yet "silent", only fresh, so it is neither counted nor evictable. A real client's
     *  reliability core answers the admission's PSTB with a bare ACK inside
     *  {@code ackFlushIdleMillis} (20 ms) and keeps sending every {@code keepaliveIdleMillis}
     *  (250 ms), so any claim still one-way past 2 s never ran a client core; the
     * NAT-household false positive was exactly that sub-second window. Volatile: rows
     *  shrink it through {@link #setPendingSquatMinAgeMillis}; the product never writes it. */
    public static final long PENDING_SQUAT_MIN_AGE_MILLIS = 2_000L;
    private volatile long pendingSquatMinAgeMillis = PENDING_SQUAT_MIN_AGE_MILLIS;
    /** RuntimeExceptions the timer belt caught
     *  (written by the timer thread only; printed as {@code timer_faults=} on the door line). */
    private volatile long timerFaults;
    /** wq P25: squat victims owed their KICKED notice — appended under the lock
     *  by {@link #evictPendingSquatLocked} (the kill must happen inside the locked HELLO resolve
     *  so the re-claim finds the seat), drained OUTSIDE it by {@link #drainPendingEvictNotices}
     *  (the core-send lock-order rule of {@link #sendKickedNoticeBurst}). */
    private final java.util.ArrayDeque<PeerSession> pendingEvictNotices =
            new java.util.ArrayDeque<PeerSession>(); // guarded by lock

    /** Drip law: at most this many LSTD frames leave one
     * session's queue per serve tick ({@link #dripListingFrames}). THIS metering is the CTRL
     * lane's real burst bound (never the reliability spool size) — a
     * max-cap 1785-entry/255-frame listing delivers in 32 ticks (~0.5 s), the full 785-id
     * vanilla debug catalogue (113 frames) in 15. */
    static final int LISTING_FRAMES_PER_TICK = 8;

    /**
     * One admitted peer's session — the extraction of the original single-slot
     * endpoint fields. <b>Identity is FINAL</b> (session id, nonce, WELCOME bytes, source address,
     * credential bindings, reliability core): takeover/displacement REPLACES the object in the
     * tables, never mutates identity — which is also what lets {@link #emitFromCore} read the
     * destination lock-free (the old volatile-read dance is gone). Mutable per-session state is
     * guarded by the endpoint's one global {@code lock} (N ≤ 31 sessions at 25 ms wait slices —
     * contention is trivial; the lock-order rule is unchanged).
     *
     * <p>The public methods are the per-session handle surface the N serve loop (step 5) and the
     * endpoint smoke drive.
     */
    public final class PeerSession {
        // ---- identity (final — replace-never-mutate) ----
        final long sessionId;
        final long helloNonce;
        final byte[] welcomeDatagram; // idempotent resend for a retransmitted HELLO (same nonce)
        /** hs-13: lane iv's {@code /auth} inputs; null on a v12 session; lock-guarded; NEVER in {@link #copyGateStateFrom} (a takeover is a new F2 with its own root). */
        byte[] hs13SessionRoot, hs13AuthScalar;
        /** The same law (null / 0 on a v12 session, lock-guarded,
         *  NEVER in {@link #copyGateStateFrom}) — {@code hs13AuthShare} = the door's {@code YbAuth}, set in
         *  {@code admitLocked} beside the root; {@code hs13AuthHighest} = the highest {@code /auth} counter the guess
         *  ledger GRANTED (u32 in a long, 0..2^32-1; 0 = none yet), advanced before the X25519 ({@code PidsChatVerbs}). */
        byte[] hs13AuthShare;
        long hs13AuthHighest;
        final InetSocketAddress peer;
        final String clientId;
        /** The custom-variant AUTHOR key of a cid-keyed door
         *  ({@link PilotIntentDatagramServer#customAuthorKey}) = {@link PilotIntentDatagramServer#authorDigestOf}
         *  of {@link #clientId}:
         *  {@code "h1:"} + 32 lowercase hex chars (length 35, ASCII). WHY a digest: on a password door the cid IS
         *  the seat key (the same cid re-claims its seat), and the author key is written whole into the match
         *  record's VSUB row, so the record carries a one-way digest, never the key. UNKEYED on purpose: the harm
         *  is recovering the cid, a 128-bit random cid has no feasible preimage, and the blocked-author list lives
         *  in memory only (it never survives a serve), so a per-host secret would guard nothing. WHY the prefix: a
         *  kit cid is already 32 hex, so the prefix is what tells a digest from an old record's raw cid. Built
         *  ONCE in the constructor so the sim-thread readers (the 1/s ops seat rows, the operator custom verbs)
         *  never hash per call. Final — replace-never-mutate. */
        final String cidAuthorDigest;
        /** The authorized seat token (null only under the legacy null-authority smoke door). */
        private final String seatToken;
        /** The token's seat id ({@link MatchSeatAuthority#authorizedSeatId}); -1 when unbound. */
        final int seatId;
        /** The token's authorized wire/ship id (the intent-pinning key); -1 when unbound. */
        final long shipId;
        /** this session's HELLO presented the ADMIN credential (per-seat WELCOME
         * bit3; never copied on takeover — a successor re-presents its own credential).
         * No longer final — {@code /auth <password>} elevates a LIVE session
         * mid-game ({@link #elevateAdmin}, reader thread under {@code lock}). Elevation dies
         * with the session like the HELLO-derived bit (a takeover/rejoin re-presents or
         * re-{@code /auth}es; nothing copies admin across sessions). Volatile: dispatch reads
         * ride the reader thread but summary/serve-loop reads may not. */
        volatile boolean admin;
        /** An OBSERVER session — seat 0 / ship 0 (seat ids are ≥1 by
         * descriptor law, so 0 aliases nothing), no seat token, no ledger claim. Receives the
         * full DOWN fan-out; its PINT deposits and seat-scoped CTRL/verbs are dropped at
         * ingest. Identity-final like its siblings (a takeover re-presents the bit). */
        final boolean observer;
        /** this session's ban NUMBERING — the cid KEYS
         * every ban listing served to it (the /unban menu, every /bans page) shares, which
         * {@code /unban #n} indexes by KEY with a live re-check; {@code null} = no pin ⇒
         * {@code /unban #n} refuses (list first). The numbering is
         * TOMBSTONE-STABLE for the session's life — a lifted key keeps its slot, new cid keys
         * append ({@link AdminBanCommand#mergedPin}), so a view this session was shown earlier never resolves to a DIFFERENT ban; past
         * {@link BanSet#MAX_ENTRIES} slots the fresh list (the one renumber). READER THREAD ONLY
         * (set and read by the chat dispatch; never under the lock, never read elsewhere); dies
         * with the session, NOT copied on takeover. */
        List<String> banListingPin;
        /** The last {@code /ban} MENU served to this
         * session — hull wire id → the session id its row showed; {@code /ban #id} requires the
         * pinned session while one exists ({@link AdminBanCommand}'s class law); {@code null} = no
         * menu served (a typed {@code #id} resolves live). The {@link #banListingPin} law: READER
         * THREAD ONLY, dies with the session, NOT copied on takeover. */
        java.util.Map<Long, Long> banMenuPin;
        /** The {@code /kick} twin of
         * {@link #banMenuPin} (the last {@code /kick} menu; same thread + lifetime law). */
        java.util.Map<Long, Long> kickMenuPin;

        /** The mid-game admin grant (lock held), from one of two sites: the door's /auth branch, after
         * the constant-time compare against the armed admin secret passed, and the
         * operator's {@code admin-cid} arm, whose key matched this live pilot's cid
         * ({@link PilotIntentDatagramServer#armAdminCid}). */
        void elevateAdmin() {
            admin = true;
        }
        /** The HELLO v8 variant pick carried out of the admit
         * lock for the reader thread's post-spool apply — non-null ONLY on a FRESH claim that
         * named a variant (a REJOINED cid / token takeover never re-spawns, so its pick is
         * dropped at admit); consumed once by {@link #applyVariantPick}. */
        private String pendingVariantPick;
        /** {@link #pendingVariantPick} came from the server-owned joiner default, not
         * the HELLO (log tag only — the seed path is identical). */
        private boolean pendingPickIsDefault;
        /** this admit is a NEW tenure (a fresh claim of
         * an unmounted seat) — guarded by the endpoint lock; set in the {@code freshClaim} block,
         * taken once by {@link #applyVariantPick}, which passes it as {@code freshTenure} (the
         * tenure law: a pick that does not seed clears the previous tenure's standing override). */
        private boolean pendingTenureApply;
        private final DatagramReliability reliability;

        // ---- mutable state (guarded by the endpoint lock) ----
        SeatNameFrameCodec.SeatNameAnnounce seatNameAnnounce;
        private PilotIntentFrame depositedIntent; // the SNAP UP latest-wins one-slot deposit
        private long depositStampTick = NO_STAMP; // R = latestStampTick at the fresh deposit (see IntentTakeAge)
        private long takenStampTick = IntentTakeAge.NOT_FRESH; // R handed to the sim thread by the take
        /** The edge bits the LAST take OR-ed onto its fresh frame
         *  (0 on every other path, the pristine re-take included); set under {@link #lock} by the take,
         *  read by the take's own caller (the sim thread) for the applied-input record. */
        private int takenFoldMask;
        /** OR of the {@link #EDGE_HELD_LATCH_MASK} bits across every PINT
         * deposited since the last take (the per-tick rising-edge latch). The one-slot deposit
         * conflates ~7 client frames per sim tick at high fps, and a 1-render-frame system
         * press (orion active=0.001 s) survives only as its window's LAST frame — one recorded run
         * measured 7 of 9 arrived presses dying exactly here. The take folds these bits onto
         * the surviving frame so an arrived press is never conflated away; cleared on take.
         * NOT copied on takeover (the depositedIntent precedent). */
        private int pendingEdgeHeldOr;
        /** The fold-rides-ONE-tick guarantee. The serve loop stages
         * whatever the take returns ({@code PilotIntentStep.stage} = put; the drain is
         * non-consuming), so a folded bit-HIGH frame would re-apply on every deposit-starved
         * tick until the next deposit — a phantom re-press for any consumer without an
         * engine-side no-op window. Discipline (the {@code PeerSendGate.lastRaw} parallel:
         * fold into the outgoing copy, keep the pristine frame for re-serves): when a take
         * folds, the pre-fold surviving frame parks here; the NEXT starved take returns it
         * exactly once so the caller re-stages pristine bits. A fresh deposit clears it
         * (the new frame replaces the staged one regardless). NOT copied on takeover. */
        private PilotIntentFrame pristineRestage;
        private final java.util.ArrayDeque<FireDischargeDeclaration> pendingDeclarations =
                new java.util.ArrayDeque<FireDischargeDeclaration>();
        /** Pending AWSD NACK entityIds — coalescing by id (a dup NACK for
         * an already-pending id is the SAME ask; the rejected epoch is advisory, never stored).
         * Reader thread deposits under the endpoint lock; the sim thread drains via
         * {@link #takeAwsdNacksOrEmpty()} BEFORE compose. NOT copied on takeover — a successor
         * session's client re-NACKs from its own state. */
        final java.util.LinkedHashSet<Long> pendingAwsdNacks =
                new java.util.LinkedHashSet<Long>();
        /** L6 CTRL spool: frames refused by a full send window queue here
         * (bound {@link #CTRL_SPOOL_BOUND}) and drain from the timer thread — the N serve's sim
         * thread never blocks on a slow peer. */
        private final java.util.ArrayDeque<byte[]> ctrlSpool = new java.util.ArrayDeque<byte[]>();
        /** Chat token bucket (guarded by lock; starts full, {@code 0} refill stamp = untouched).
         * COPIED on a same-key takeover: the burst is per admitted KEY, not per session;
         * the elapsed refill runs off the carried stamp (an honest reconnect after a gap starts full; a swept dead row or a NEW cid = a fresh bucket, admission-paced). */
        double chatTokens = CHAT_BUCKET_BURST;
        long chatBucketRefillMillis;
        /** W15 v0 VSUB token bucket + hostile-refusal count (guarded by lock; the chat-bucket
         * shape — the law itself lives in {@link CustomVariantLane.SubmitBucket}).
         * The RATE bucket is COPIED on a same-key takeover
         * (per admitted KEY, the chat bucket's shape); the hostile count stays per session
         * (a carried count never resets and kick-loops an honest tier-refused custom). */
        final CustomVariantLane.SubmitBucket variantSubmit =
                new CustomVariantLane.SubmitBucket();
        /** F9 owner-mark rate law (guarded by lock; {@code 0} = none accepted
         * yet). NOT copied on takeover (the bytesSent precedent). */
        long lastMarkAcceptMillis;
        /** The PSTE stdout row's twin of the mark law (guarded by lock). */
        long lastPostureEchoLogMillis;
        /** W7b pending {@code /suicide} flag (guarded by lock): the reader-thread
         * dispatcher DEPOSITS only — applyDamage is sim-thread work, so the serve loop drains
         * via {@link #takeSuicideRequested()} and kills on the next advance. Boolean = repeat
         * sends before a drain coalesce to ONE kill. NOT copied on takeover (the
         * bytesSent/pendingAwsdNacks precedent). */
        boolean pendingSuicide;
        /** The side (0 / 1) this seat's next fly-in must use, decided by the door under its lock and
         * not yet taken by the serve, or {@code -1} = none (guarded by lock). The reader thread
         * deposits only — a {@code /team} SET, an admitted claim whose seat plays for the other team,
         * an admin switch in the PvP shape — and repeat deposits before a drain coalesce (the last
         * wins); the serve takes it through {@link PilotIntentDatagramServer#drainTeamPicks} after the admin drain.
         * A per-session field, never the admin queue: 255 players must not evict admin receipts. NOT
         * copied on takeover (the pendingSuicide precedent): the successor's admission deposits its own.
         * */
        int pendingTeamSide = -1;
        /** The cross-fill tell this session is owed (a full team pick: seated on the other team, or a held seat
         * kept on its own), or null (guarded by lock). Deposited only by the admission that placed the claim
         * (edge words + digits, never player text); sent ONCE by {@link PilotIntentDatagramServer#drainTeamPicks}
         * under the pick's gate, then cleared. A replay door admits no HELLO, so it never holds one. NOT copied
         * on takeover (the pendingTeamSide precedent). */
        String pendingTeamNotice;
        /** Per-seat LSTD listingId mint (latest-wins — ids
         * strictly increase within a session; NOT copied on takeover, the pendingSuicide
         * precedent: a successor's client assembles from its own fresh state) + the drip
         * queue {@link #dripListingFrames} drains ≤{@link #LISTING_FRAMES_PER_TICK}/tick.
         * A new mint CLEARS the queue first — a superseded listing's undripped remainder
         * never rides (the client's latest-wins assembly drops the partial). Guarded by
         * lock; reader thread mints/enqueues, serve loop drains. */
        int lastListingId;
        final java.util.ArrayDeque<byte[]> listingDrip =
                new java.util.ArrayDeque<byte[]>();
        /** Peer-build sharing v2: the seat's fielding auto-share flag — set by the
         * client's {@code /share auto on|off silent} at admission + on toggle (default OFF, so a
         * pre-v2 kit changes nothing); read by {@link #recordSeatFieldedVariant}. Guarded by lock. */
        private boolean autoShareBuilds;
        private PilotPeerDisconnectedException pendingDeath;
        boolean peerSaidBye;
        private boolean byeSent;
        private boolean byeAcked;
        boolean seatVacated; // the claim released exactly once per session (idempotence); pkg-private for PendingSquatLaw
        /** A ban is ending this session — its seat release
         * rides {@code vacateBanned} (PARKED at once, never a reconnect hold). Guarded by lock. */
        boolean bannedVacate;
        private int declarationsSinceLastIntent; // the per-exchange budget analog
        private long pinnedIntentDropCount;
        private long pinnedDeclarationDropCount; // cross-seat PDCL drops
        /** Per-seat bandwidth twins (marginal-burden attribution): TX = this
         * session's datagrams that reached the socket (WELCOME + core emissions + BYE); RX =
         * validated in-session arrivals (handshake HELLO bytes stay endpoint-level). Atomic —
         * writers span the core-monitor/timer/reader threads. NOT copied on takeover: a
         * successor session starts its own accounting. */
        private final AtomicLong bytesSent = new AtomicLong();
        private final AtomicLong bytesReceived = new AtomicLong();
        /** this session's SNAP send-gap meter — SIM THREAD ONLY (written by
         * the N serve's per-session fan, read by its round / receipt / serve-end rows on the same thread); the door
         * never touches it. NOT copied on takeover (the bytesSent precedent): every admit mints a fresh one. */
        final SnapSendGapMeter snapSendGap = new SnapSendGapMeter();

        // ---- liveness (per-session; copied old->new on displacement/takeover so re-admission
        // preserves the legacy endpoint-level survival of these values) ----
        private volatile int livenessReadDeadlineMillis;
        private volatile int livenessDeclarationBudget;
        private volatile long livenessJoinGraceMillis;
        private volatile boolean livenessJoinGracePending;
        volatile long lastValidArrivalMillis;
        /** TWO-WAY established — the first valid in-session datagram arrived
         * (set in {@link #markArrivalLocked}). Deliberately NOT in {@link #copyGateStateFrom}:
         * a takeover successor starts false (the SN4 {@code seatNameAnnounce} precedent) — the
         * flap re-admitted 15 half-open successors that never moved a byte, and every
         * announce/toast keyed on admission alone churned the room. Presence lines (join line,
         * grace toasts) key on THIS flag, never on admission. */
        volatile boolean reachedTwoWay; // pkg-private for PendingSquatLaw
        /** Successor of a LIVE announced/suppressed same-key predecessor = a
         * RECONNECT the room never saw leave — no join line; a dead (BYE'd / failed) predecessor's successor announces. */
        volatile boolean joinSuppressed;
        /** Seat squat: when this claim's never-two-way wait began — the
         * admit instant, INHERITED across a same-key takeover whose predecessor had not reached
         * two-way either (a squatter re-HELLOing its own cid never resets its age; a settled
         * predecessor's successor starts fresh, like {@link #reachedTwoWay}). Door lock. */
        long pendingSinceMillis; // pkg-private for PendingSquatLaw

        // ---- DOWN stamp (volatile: written under lock by the serve loop's sendTickToSession, read lock-free by
        // the reader's UP deposit / sink record = IntentTakeAge's R; the PDCL gate beside it was deleted) ----
        private volatile long latestStampTick = NO_STAMP;

        /** A session minted from a match record on a replay door
         *  ({@link #replayMint}) — no peer exists, so its CTRL never enters the reliability core
         *  ({@link #ctrlSpoolOrFail}), its tick sends stop after the stamp ({@link #sendTickToSession}), its
         *  liveness never arms ({@link #configureLiveness}) and the timer never services it. False on every
         *  live-door session. */
        final boolean replay;

        PeerSession(long sessionId, long helloNonce, byte[] welcomeDatagram,
                InetSocketAddress peer, String clientId, String seatToken, int seatId, long shipId,
                boolean admin, boolean observer, boolean replay) {
            this.replay = replay;
            this.sessionId = sessionId;
            this.helloNonce = helloNonce;
            this.welcomeDatagram = welcomeDatagram;
            this.peer = peer;
            this.clientId = clientId;
            this.cidAuthorDigest = authorDigestOf(clientId);
            this.seatToken = seatToken;
            this.seatId = seatId;
            this.shipId = shipId;
            this.admin = admin;
            this.observer = observer;
            // W12 L2: the relay's view of this session — identity is final, so build it once.
            this.voiceEndpoint = new VoiceRelay.Endpoint(sessionId, seatId, shipId, this);
            // The core's callbacks close over this session: emissions carry its final identity,
            // deliveries land in its own deposit/queues.
            this.reliability = new DatagramReliability(DatagramReliability.Config.defaults(),
                    emission -> emitFromCore(this, emission),
                    (seq, payload) -> onCtrlDelivered(this, seq, payload),
                    (seq, payload) -> onSnapDelivered(this, seq, payload));
        }

        /** The {@link #cidAuthorDigest} built at construction (never recomputed). */
        String cidAuthorDigest() {
            return cidAuthorDigest;
        }

        /** The relay mute rows lifted off this (dropped) session at its table
         * drop ({@link VoiceRelay#detachMutes}, {@link #removeSessionLocked}); a same-key
         * takeover re-attaches them under the successor in {@link #admitLocked}; any other drop
         * leaves them in the door's by-key carry ({@link #carriedMutesByKey})
         * for the key's next FRESH admission. Door lock. */
        private VoiceRelay.MuteCarry carriedMutes;

        private void copyGateStateFrom(PeerSession predecessor) {
            this.livenessReadDeadlineMillis = predecessor.livenessReadDeadlineMillis;
            this.livenessDeclarationBudget = predecessor.livenessDeclarationBudget;
            this.livenessJoinGraceMillis = predecessor.livenessJoinGraceMillis;
            this.livenessJoinGracePending = predecessor.livenessJoinGracePending;
            this.latestStampTick = predecessor.latestStampTick;
            if (!predecessor.reachedTwoWay) {
                this.pendingSinceMillis = predecessor.pendingSinceMillis; // The squat age survives its own re-HELLO
            }
            this.chatTokens = predecessor.chatTokens; // Burst per KEY
            this.chatBucketRefillMillis = predecessor.chatBucketRefillMillis;
            this.variantSubmit.copyFrom(predecessor.variantSubmit); // the RATE bucket per KEY (not the hostile count)
            this.joinSuppressed = !predecessor.observer && predecessor.isLiveLocked() && (predecessor.reachedTwoWay || predecessor.joinSuppressed); // RL-2; an observer was never announced
        }

        boolean isLiveLocked() { // pkg-private for PendingSquatLaw
            return pendingDeath == null && !peerSaidBye && !closed;
        }

        private final VoiceRelay.Endpoint voiceEndpoint;

        /** W12 L2: the relay's endpoint record ({@code handle} = this). */
        public VoiceRelay.Endpoint voiceEndpoint() {
            return voiceEndpoint;
        }

        // ---- the per-session handle surface (step 5's serveNPeer drives these) ----

        public long sessionId() {
            return sessionId;
        }

        public String clientId() {
            return clientId;
        }

        public String seatToken() {
            return seatToken;
        }

        public int seatId() {
            return seatId;
        }

        public long shipId() {
            return shipId;
        }

        /** true iff this session presented the admin credential at its HELLO. */
        public boolean admin() {
            return admin;
        }

        /** true iff this is an OBSERVER session (seat 0 / ship 0, no hull —
         * the serve loop's census renders it {@code obs}; nothing else keys on it because an
         * observer never deposits an intent, so no seat-side path ever sees seat 0). */
        public boolean observer() {
            return observer;
        }

        /** Ops-GUI seat name (it once showed only the client id): the
         * seat's WIRE-announced name (SNAM, first-write-wins under the door lock), or null
         * when the peer announced none / an empty one. Peer-controlled text — the ops
         * writer bounds it ({@code OpsStatusFile.sanitize}); it never re-enters the wire. */
        public String announcedSeatNameOrNull() {
            SeatNameFrameCodec.SeatNameAnnounce announce;
            synchronized (lock) {
                announce = seatNameAnnounce;
            }
            return announce != null && announce.hasName() && !announce.name().isEmpty()
                    ? announce.name() : null;
        }

        public InetSocketAddress peerAddress() {
            return peer;
        }

        /** Live = admitted with no pending death, no peer goodbye, endpoint open. */
        public boolean isLive() {
            synchronized (lock) {
                return isLiveLocked();
            }
        }

        /** Non-blocking take of the latest-wins {@code PINT} deposit (null = none since last take),
         * with the window's ORed edge bits folded on (see {@link #pendingEdgeHeldOr}).
         * A starved take directly after a folded one returns the PRISTINE surviving frame once
         * instead of null, so the caller re-stages it and the fold rides exactly ONE applied
         * tick (see {@link #pristineRestage}). */
        public PilotIntentFrame takeDepositedIntentOrNull() {
            synchronized (lock) {
                PilotIntentFrame taken = depositedIntent;
                depositedIntent = null;
                takenStampTick = taken == null ? IntentTakeAge.NOT_FRESH : depositStampTick;
                int edgeOr = pendingEdgeHeldOr;
                pendingEdgeHeldOr = 0;
                takenFoldMask = 0;
                if (taken == null) {
                    PilotIntentFrame restage = pristineRestage;
                    pristineRestage = null;
                    return restage;
                }
                pristineRestage = null;
                int folded = edgeOr & ~taken.heldControls();
                if (folded != 0) {
                    pristineRestage = taken;
                    takenFoldMask = folded;
                    taken = taken.withHeldControls(taken.heldControls() | edgeOr);
                }
                return taken;
            }
        }

        /** The deposit stamp R of the LAST take's fresh frame, {@code IntentTakeAge.NOT_FRESH} after a null/pristine re-take (sim thread: the take's own caller). */
        public long takenDepositStampTick() { return takenStampTick; }

        /** The edge bits the LAST take folded onto its frame, 0 after a fresh-as-sent, null or pristine take (sim thread: the take's own caller). */
        public int takenFoldMask() { return takenFoldMask; }

        /** Dequeue this session's oldest demuxed declaration, or null (FIFO, never conflated). */
        public FireDischargeDeclaration pollDeclaration() {
            synchronized (lock) {
                return pendingDeclarations.pollFirst();
            }
        }

        /** Take-all drain of this session's pending AWSD-NACK entityIds (empty = none since
         * the last take). Sim thread, BEFORE compose — the composer folds
         * the ids into its pending-keyframe set so the re-key rides THIS tick. */
        public List<Long> takeAwsdNacksOrEmpty() {
            synchronized (lock) {
                if (pendingAwsdNacks.isEmpty()) {
                    return List.of();
                }
                List<Long> taken = new ArrayList<Long>(pendingAwsdNacks);
                pendingAwsdNacks.clear();
                return taken;
            }
        }

        /** Take-and-clear of the pending {@code /suicide} request (serve-loop thread; the
         * drain re-checks seat authority before killing — the dispatch-time guard is only
         * advisory). */
        public boolean takeSuicideRequested() {
            synchronized (lock) {
                boolean taken = pendingSuicide;
                pendingSuicide = false;
                return taken;
            }
        }

        /** Per-session liveness arm — same knob semantics as the endpoint-level seam member. */
        public void configureLiveness(int readDeadlineMillis, int declarationBudgetPerExchange,
                long joinGraceMillis) {
            requireLivenessKnobs(readDeadlineMillis, declarationBudgetPerExchange, joinGraceMillis);
            if (replay) {
                // A replayed session has no peer to fall silent — its
                // deadline stays 0, so the silence sweep skips it by construction. The arguments are still
                // validated (the serve passes the same values in both modes) and the liveness knob itself is
                // left alone for a replay (it is behavior-class, so it must match the recording) — hence the
                // branch here rather than in the serve.
                return;
            }
            synchronized (lock) {
                this.livenessReadDeadlineMillis = readDeadlineMillis;
                this.livenessDeclarationBudget = declarationBudgetPerExchange;
                this.livenessJoinGraceMillis = joinGraceMillis;
                this.livenessJoinGracePending = readDeadlineMillis > 0 && joinGraceMillis > 0L;
            }
        }

        /** This session's reliability-core counter/gauge snapshot. */
        public DatagramReliability.Stats stats() {
            return reliability.stats();
        }

        /** Frames queued behind a full CTRL send window (the spool) — 0 in
         * steady state; reaching {@link #CTRL_SPOOL_BOUND} kills the session ASYNC with the
         * {@code ctrl_window_stall} verdict token. */
        public int ctrlSpoolDepth() {
            synchronized (lock) {
                return ctrlSpool.size();
            }
        }

        /** Free CTRL send-window slots for the paced
         * RAPP catch-up cursor — 0 while the spool is non-empty (a spooled frame means the
         * window is already full), else {@code ctrlWindow - ctrlOutstanding}. Two SEPARATE
         * monitor reads, never nested (lock-order rule: no call into {@code reliability} under
         * {@code lock}). */
        public int ctrlHeadroom() {
            if (ctrlSpoolDepth() > 0) {
                return 0;
            }
            return Math.max(0, reliability.ctrlWindow() - reliability.ctrlOutstanding());
        }

        /** True while this session's RAPP catch-up
         * cursor is active — the door's RAPP fan WITHHOLDS live frames from it (they are in
         * the round log; the cursor delivers them in log order). Serve-loop written; volatile
         * because the fan skeleton reads it lock-free. Invariant: true iff a cursor exists
         * ({@link RosterAppendCatchup}). */
        private volatile boolean rosterCatchupActive;

        /** */
        public void setRosterCatchupActive(boolean on) {
            rosterCatchupActive = on;
        }

        /** */
        public boolean rosterCatchupActive() {
            return rosterCatchupActive;
        }

        /** True once the reader thread finished this session's admission provisioning (posture
         * block, seat-name bind, variant-definition burst, pick, voice sheet) — set under
         * {@code lock} on EVERY exit of that block, a throw included. The admission cursor treats
         * an unprovisioned session as headroom 0, so its paced catch-up never lands inside the
         * variant-definition burst (that burst plus a full catch-up step would pass the CTRL kill
         * point). Never cleared. Volatile because the serve loop reads it lock-free.
         * */
        volatile boolean provisioned;

        public boolean provisioned() {
            return provisioned;
        }

        /** The last DOWN stamp {@link #sendTickToSession} wrote for this
         *  session ({@code Long.MIN_VALUE} = none yet) — the take-age read's input, kept on a replay session too
         *  (the stamp is written before the replay return). Read-only view for the re-sim rows. */
        public long latestStampTick() {
            return latestStampTick;
        }

        /** {@code PINT} deposits dropped because their shipId was not this session's authorized
         * wire id (N-mode intent pinning — the cross-seat spoof closed at the door). */
        public long pinnedIntentDrops() {
            synchronized (lock) {
                return pinnedIntentDropCount;
            }
        }

        /** {@code PDCL} declarations dropped because their shipId was not this session's
         * authorized wire id (the PINT pin's declaration twin). */
        public long pinnedDeclarationDrops() {
            synchronized (lock) {
                return pinnedDeclarationDropCount;
            }
        }

        /** Bytes of this session's datagrams handed to the socket (the per-seat TX meter). */
        public long bytesSent() {
            return bytesSent.get();
        }

        /** Bytes of validated in-session arrivals from this peer (the per-seat RX meter). */
        public long bytesReceived() {
            return bytesReceived.get();
        }

        /** The session's pending death (the fail-closed terminal), or null while healthy. */
        public PilotPeerDisconnectedException pendingDeathOrNull() {
            synchronized (lock) {
                return pendingDeath;
            }
        }

        /** Why this session left the live set — the death's reason token,
         * {@code bye} for a peer goodbye, else {@code displaced} (a takeover/eviction removed it
         * without a death). For the serve loop's {@code session_dead} row. */
        public String departureReason() {
            synchronized (lock) {
                if (pendingDeath != null) {
                    return pendingDeath.reasonToken();
                }
                return peerSaidBye ? "bye" : "displaced";
            }
        }
    }

    private PilotIntentDatagramServer(DatagramSocket socket, boolean offLoopback, String host,
            int port, MatchSeatAuthority seatAuthority, boolean multiPeer, int maxSessions,
            String requiredDoorFingerprint, String matchPassword,
            PasswordSeatLedger passwordLedger, PickableVariants pickableVariants,
            VariantCatalogStore variantCatalog, SeatRebindArbiter moduleSeats,
            boolean moduleVerbsArmed, TransferTargets transferTargets, DoorSourceHold.Policy chatSourceHold,
            SourceSeatCap sourceSeatCap, ProbeMatchDoc matchDoc, boolean replay, boolean pvp) {
        this.replay = replay;
        this.seatTeams = passwordLedger == null ? null : new SeatTeamTable(passwordLedger, pvp);
        this.matchDoc = matchDoc;
        this.socket = socket;
        this.offLoopback = offLoopback;
        this.host = host;
        this.port = port;
        this.seatAuthority = seatAuthority;
        this.multiPeer = multiPeer;
        this.maxSessions = maxSessions;
        this.requiredDoorFingerprint = requiredDoorFingerprint;
        // The Main-minted boot table (null on an engine-less
        // rig door) and the door's TWO expectations — raw for the game-free PROBE/VCHK, folded
        // (data= over vtab) for a session claim. OFF when the door is unarmed.
        this.idTables = IdTables.installedOrNull();
        this.modSet = ModSet.installedOrNull();   // Folded beside vtab (null = vtab-only, rigs)
        this.optionalModSet = ModSet.installedOptionalOrNull();   // Published on the probe, never folded
        this.doorExpectations = requiredDoorFingerprint == null ? DoorFingerprintExpectations.OFF
                : DoorFingerprintExpectations.armed(requiredDoorFingerprint,
                        idTables == null ? null : idTables.vtab(),
                        modSet == null ? null : modSet.digest());
        this.matchPassword = matchPassword;
        this.passwordLedger = passwordLedger;
        this.pickableVariants = pickableVariants;
        // W15 v0 step 3(c): the pick surface lives in its own class; the seeder
        // is the dispatch's own monitor, the notifier the door's CTRL send — both outside any lock.
        this.variantPicks = new SeatVariantPicks(pickableVariants,
                new SeatVariantPicks.Seeder() {
                    @Override
                    public SeatVariantPicks.Seed seed(int seat, String canonical) {
                        return chatCommands.seedOverride(seat, canonical, moduleSeats, pickableVariants);
                    }

                    @Override
                    public String standingOrNull(int seat) {
                        // A released hold names the pick it falls back to.
                        return chatCommands.overrideOrNull(seat);
                    }

                    @Override
                    public String clearStanding(int seat) {
                        // The tenure clear — the
                        // dispatch's own leaf monitor, never under the door lock.
                        return chatCommands.clearOverride(seat);
                    }

                    @Override
                    public boolean bound(long sessionId, int seat) {
                        // The submitter seed only lands on the session that sent the
                        // VSUB while it still holds that seat (the chatToSession lookup shape).
                        synchronized (lock) {
                            PeerSession s = sessionsById.get(sessionId);
                            return s != null && s.isLiveLocked() && s.seatId == seat;
                        }
                    }
                },
                this::chatToSession);
        this.variantCatalog = variantCatalog;
        this.moduleSeats = moduleSeats;
        this.moduleVerbsArmed = moduleVerbsArmed;
        this.transferTargets = transferTargets;
        this.sourceHold = new DoorSourceHold(chatSourceHold); // null = OFF
        this.sourceSeatCap = sourceSeatCap == null ? SourceSeatCap.OFF : sourceSeatCap;
        // The DOWN fan-out lives in CtrlFanout; the
        // sink stays a PIDS-owned volatile, read per call through the supplier.
        this.fans = new CtrlFanout(this::liveSessions, this::ctrlSpoolOrFail, lock, () -> sink, counters,
                moduleSeats, requiredDoorFingerprint, () -> admissionCursor);
        this.reader = new Thread(this::readLoop, "pilot-datagram-server-reader");
        this.reader.setDaemon(true);
        this.timer = new Thread(this::timerLoop, "pilot-datagram-server-timer");
        this.timer.setDaemon(true);
    }

    /**
     * The D4.4 N-peer door: up to {@code maxSessions} concurrent sessions over
     * the SAME one socket, token-keyed takeover, per-session intent pinning. {@code seatAuthority}
     * is REQUIRED (no perimeter-trust at N — fail-closed); {@code maxSessions} >= 1 (callers pass
     * the descriptor seat count; a smoke may bound lower). Drive
     * {@link #liveSessions()} / {@link #awaitLiveSessions(int, long)} and the {@link PeerSession}
     * handles. Every optional off ({@link PidsOpenParams#NONE}); the three narrow overloads
     * below are the test-facing shapes, the serve builds the record.
     */
    public static PilotIntentDatagramServer openMultiPeer(String bindHost, int bindPort,
            MatchSeatAuthority seatAuthority, int maxSessions) throws IOException {
        return openMultiPeer(bindHost, bindPort, seatAuthority, maxSessions, PidsOpenParams.NONE);
    }

    /** The door with the D4.7 content-fingerprint check armed (non-null; null = off). */
    public static PilotIntentDatagramServer openMultiPeer(String bindHost, int bindPort,
            MatchSeatAuthority seatAuthority, int maxSessions, String requiredDoorFingerprint)
            throws IOException {
        return openMultiPeer(bindHost, bindPort, seatAuthority, maxSessions,
                new PidsOpenParams(requiredDoorFingerprint, null, null, null, null, null, false, null, null));
    }

    /** The door in PASSWORD mode (the pair law = {@link PidsOpenParams#matchPassword}). */
    public static PilotIntentDatagramServer openMultiPeer(String bindHost, int bindPort,
            MatchSeatAuthority seatAuthority, int maxSessions, String requiredDoorFingerprint,
            String matchPassword, PasswordSeatLedger passwordLedger) throws IOException {
        return openMultiPeer(bindHost, bindPort, seatAuthority, maxSessions,
                new PidsOpenParams(requiredDoorFingerprint, matchPassword, passwordLedger, null, null, null, false, null, null));
    }

    /** The door with the W7 B1b {@code /ship} pickable allowlist (nullable = picking unavailable). */
    public static PilotIntentDatagramServer openMultiPeer(String bindHost, int bindPort,
            MatchSeatAuthority seatAuthority, int maxSessions, String requiredDoorFingerprint,
            String matchPassword, PasswordSeatLedger passwordLedger,
            PickableVariants pickableVariants) throws IOException {
        return openMultiPeer(bindHost, bindPort, seatAuthority, maxSessions,
                new PidsOpenParams(requiredDoorFingerprint, matchPassword, passwordLedger, pickableVariants, null, null, false, null, null));
    }

    /** The widest door: the required four + every optional as one {@link PidsOpenParams}. */
    public static PilotIntentDatagramServer openMultiPeer(String bindHost, int bindPort,
            MatchSeatAuthority seatAuthority, int maxSessions, PidsOpenParams p) throws IOException {
        if (seatAuthority == null) {
            throw new IllegalArgumentException("the multi-peer door requires a real seat authority"
                    + " (no perimeter-trust at N — fail-closed)");
        }
        if (maxSessions < 1) {
            throw new IllegalArgumentException("maxSessions must be >= 1, got: " + maxSessions);
        }
        return openInternal(bindHost, bindPort, seatAuthority, true, maxSessions,
                p.requiredDoorFingerprint(), p.matchPassword(), p.passwordLedger(), p.pickableVariants(),
                p.variantCatalog(), p.moduleSeats(), p.moduleVerbsArmed(), p.transferTargets(),
                p.bootBans(), p.chatSourceHold(), p.sourceSeatCap(), p.matchDoc(), false, p.pvp());
    }

    /**
     * The REPLAY door: {@link #openMultiPeer}'s guards and parameters, but bound to {@code 127.0.0.1:0} (hard-coded,
     * never a config read — the door is unreachable, so a session id forced from a record can never meet a live
     * HELLO), its reader thread never started, and every send swallowed ({@link #suppressedSends}). Sessions come
     * only from {@link #replayMint} and leave only through {@link #replayDie} or {@link #close}; the re-sim feed
     * drives them from a recorded match's rows.
     */
    public static PilotIntentDatagramServer openReplay(MatchSeatAuthority seatAuthority, int maxSessions,
            PidsOpenParams p) throws IOException {
        if (seatAuthority == null) {
            throw new IllegalArgumentException("the multi-peer door requires a real seat authority"
                    + " (no perimeter-trust at N — fail-closed)");
        }
        if (maxSessions < 1) {
            throw new IllegalArgumentException("maxSessions must be >= 1, got: " + maxSessions);
        }
        return openInternal("127.0.0.1", 0, seatAuthority, true, maxSessions,
                p.requiredDoorFingerprint(), p.matchPassword(), p.passwordLedger(), p.pickableVariants(),
                p.variantCatalog(), p.moduleSeats(), p.moduleVerbsArmed(), p.transferTargets(),
                p.bootBans(), p.chatSourceHold(), p.sourceSeatCap(), p.matchDoc(), true, p.pvp());
    }

    /**
     * (83) SV-3: the same strict knob the binding factory reads — one knob
     * governs "refuse vs announce+degrade" for every bind-tier fallback. Default TRUE.
     */
    private static boolean bindStrict() {
        return ServerConfig.resolveBoolean("cc.bind.strict", "CC_BIND_STRICT", "bind.strict", true);
    }

    /**
     * The door's bind-address rules, ONE home for {@link #openInternal} and {@link #probeBind}
     * (the probe must fail exactly where the real bind would): blank host =
     * "pick my NIC" ({@link BindAddressPick#blankBindOrRefuse}: a site-local IPv4 only; none ⇒
     * strict refuses, else announce + loopback — (83) SV-3; every loopback-wanting
     * rig passes an explicit 127.0.0.1), {@code wildcard} = {@code null} (the socket binds
     * the any-address; its ADVERTISED address is {@link BindAddressPick#wildcardAdvertiseOrRefuse}),
     * else the literal/name resolved.
     */
    private static InetAddress resolveBindAddress(String requested, boolean wildcard)
            throws IOException {
        if (requested.isEmpty()) {
            return BindAddressPick.blankBindOrRefuse(BindAddressPick.upNonLoopbackAddresses(), PilotIntentDatagramServer::bindStrict);
        }
        return wildcard ? null : InetAddress.getByName(requested);
    }

    /**
     * Bind-and-close the door's
     * endpoint on {@link #resolveBindAddress}'s exact rules so a dead VPN lease / taken port
     * fails a supervised life in ~1 s with the endpoint NAMED — {@code PilotIntentCombatServerMain}
     * calls this BEFORE the ~50 s engine boot; the real bind ({@link #openInternal}) sat after it,
     * so five lives × 50 s of "STARTING" preceded a bare {@code BindException}. Residual TOCTOU
     * accepted: the real bind still fails closed. Nothing the bind needs comes from the engine.
     * A {@code 0.0.0.0} probe also takes the real door's advertise decision
     * ({@link BindAddressPick#wildcardAdvertiseOrRefuse}) BEFORE its socket opens, so a box with no
     * dialable IPv4 fails here too, not after the engine boot.
     */
    public static void probeBind(String bindHost, int bindPort) throws IOException {
        String requested = bindHost == null ? "" : bindHost.trim();
        boolean wildcard = "0.0.0.0".equals(requested);
        InetAddress bind = resolveBindAddress(requested, wildcard);
        if (wildcard) {
            BindAddressPick.wildcardAdvertiseOrRefuse(BindAddressPick.upNonLoopbackAddresses(), PilotIntentDatagramServer::bindStrict);
        }
        String named = (wildcard ? "0.0.0.0" : bind.getHostAddress()) + ":" + bindPort;
        DatagramSocket socket = new DatagramSocket(null);
        try {
            if (bindPort == 0) {
                socket.setReuseAddress(true);
            }
            socket.bind(wildcard ? new InetSocketAddress(bindPort)
                    : new InetSocketAddress(bind, bindPort));
            System.out.println("npeer-udp-bind-probe host=" + named + " ok");
        } catch (SocketException bindFailure) {
            BindException why = new BindException("bind probe FAILED for " + named
                    + " (VPN down / stale lease / port taken? the door binds this endpoint after"
                    + " engine boot): " + bindFailure.getMessage());
            why.initCause(bindFailure);
            throw why;
        } finally {
            socket.close();
        }
    }

    private static PilotIntentDatagramServer openInternal(String bindHost, int bindPort,
            MatchSeatAuthority seatAuthority, boolean multiPeer, int maxSessions,
            String requiredDoorFingerprint, String matchPassword,
            PasswordSeatLedger passwordLedger, PickableVariants pickableVariants,
            VariantCatalogStore variantCatalog, SeatRebindArbiter moduleSeats,
            boolean moduleVerbsArmed, TransferTargets transferTargets, BanSet bootBans,
            DoorSourceHold.Policy chatSourceHold, SourceSeatCap sourceSeatCap, ProbeMatchDoc matchDoc,
            boolean replay, boolean pvp) throws IOException {
        if ((matchPassword == null) != (passwordLedger == null)) {
            throw new IllegalArgumentException("password mode is a PAIR — matchPassword and"
                    + " passwordLedger arrive together or not at all; got password="
                    + (matchPassword == null ? "null" : "set") + " ledger="
                    + (passwordLedger == null ? "null" : "set"));
        }
        if (matchPassword != null && matchPassword.isBlank()) {
            throw new IllegalArgumentException("matchPassword must be non-blank when password mode"
                    + " is armed (the sidecar parser already fail-closes this — a blank here is a"
                    + " wiring bug)");
        }
        if (matchPassword != null && !multiPeer) {
            throw new IllegalArgumentException(
                    "password mode requires the npeer door");
        }
        if (requiredDoorFingerprint != null && (requiredDoorFingerprint.isBlank()
                || requiredDoorFingerprint.length() > 128)) {
            // Fail-closed config: an armed door with a blank/over-cap expectation (a client could
            // never present it) never opens silently (the arming rule).
            throw new IllegalArgumentException("requiredDoorFingerprint must be non-blank and"
                    + " <= 128 chars when armed; got " + requiredDoorFingerprint.length()
                    + " chars");
        }
        String requested = bindHost == null ? "" : bindHost.trim();
        boolean wildcard = "0.0.0.0".equals(requested);
        InetAddress bind = resolveBindAddress(requested, wildcard);
        boolean offLoopback = wildcard || !bind.isLoopbackAddress();
        DatagramSocket socket = new DatagramSocket(null);
        if (bindPort == 0) {
            socket.setReuseAddress(true);
        }
        try {
            socket.bind(wildcard ? new InetSocketAddress(bindPort)
                    : new InetSocketAddress(bind, bindPort));
        } catch (SocketException bindFailure) {
            socket.close();
            throw bindFailure;
        }
        // The door is ONE internet-facing
        // socket for every session, CRC-decoded BEFORE any source check on the single reader
        // thread — the kernel stays the cheapest DoS filter, so 1 MiB (the UdpPathologyRelay
        // precedent), never the joiner's 4 MiB. 0 = OS default.
        int rcvBufWant = Integer.getInteger("cc.net.doorRcvBufBytes", 1 << 20);
        if (rcvBufWant > 0) {
            rcvBufWant = Math.max(65_536, Math.min(4 << 20, rcvBufWant));
            try {
                socket.setReceiveBufferSize(rcvBufWant);
            } catch (SocketException e) {
                System.out.println("npeer-udp door_rcvbuf_set_failed want=" + rcvBufWant + " err=" + e);
            }
        } else {
            rcvBufWant = 0;
        }
        InetAddress advertised = bind;
        if (wildcard) {
            // A wildcard bind exists to serve remote
            // clients; with no dialable IPv4 to advertise, strict (default) refuses — the bound
            // socket is closed first, then the refusal rethrown.
            try {
                advertised = BindAddressPick.wildcardAdvertiseOrRefuse(
                        BindAddressPick.upNonLoopbackAddresses(), PilotIntentDatagramServer::bindStrict);
            } catch (IllegalStateException noDialableIpv4) {
                socket.close();
                throw noDialableIpv4;
            }
        } else {
            // A WFP bind redirect (e.g. a VPN split-tunnel layer) can place the
            // socket on a DIFFERENT local address than requested — the requested endpoint then
            // has no socket at all and every client HELLO to it silently drops (measured:
            // received=0 advertised-side vs received=20 actual-side). Advertise the address the
            // OS actually gave the socket, never the request.
            InetAddress actual = socket.getLocalAddress();
            if (actual != null && !actual.isAnyLocalAddress() && !actual.equals(bind)) {
                System.out.println("pilot-udp-door bind_redirected requested="
                        + bind.getHostAddress() + " actual=" + actual.getHostAddress()
                        + " (WFP/VPN bind redirect; advertising actual)");
                advertised = actual;
                offLoopback = !actual.isLoopbackAddress();
            }
        }
        // The seat allowance's exempt set is bound HERE, after the advertised address is final: loopback +
        // the advertised address + every up non-loopback interface address (the host's own windows dial any
        // of them, a VPN hairpin may arrive on the tunnel address); printed once so a widened exemption is
        // never silent. OFF stays OFF, enumerates nothing and prints nothing (every rig door, every token door:
        // their open is byte-identical on stdout).
        SourceSeatCap boundCap = sourceSeatCap == null || !sourceSeatCap.armed() ? SourceSeatCap.OFF
                : sourceSeatCap.boundTo(advertised, BindAddressPick.upNonLoopbackAddresses());
        PilotIntentDatagramServer endpoint = new PilotIntentDatagramServer(socket, offLoopback,
                advertised.getHostAddress(), socket.getLocalPort(), seatAuthority, multiPeer,
                maxSessions, requiredDoorFingerprint, matchPassword, passwordLedger,
                pickableVariants, variantCatalog, moduleSeats, moduleVerbsArmed,
                transferTargets, chatSourceHold, boundCap, matchDoc, replay, pvp);
        if (boundCap.armed()) {
            System.out.println("npeer-udp-door source_seat_cap " + boundCap.describe()
                    + " (" + SourceSeatCap.ROW + "; password door only; spectators never count)");
        }
        if (endpoint.doorExpectations.armed()) {
            // The fold is visible at bind — ops reads which print the
            // door expects of a session claim, and whether a table backed it.
            System.out.println("npeer-udp-door fingerprint=armed folded=" + endpoint.doorExpectations.folded()
                    + " vtab=" + (endpoint.idTables == null ? "none" : endpoint.idTables.vtab())
                    + " modset=" + (endpoint.modSet == null ? "none" : endpoint.modSet.digest() + " n=" + endpoint.modSet.size())
                    + " session_data=" + ContentFingerprints.componentOrNull(endpoint.doorExpectations.session(), "data")
                    + " raw_data=" + ContentFingerprints.componentOrNull(endpoint.doorExpectations.raw(), "data"));
        }
        try {
            // B5 E1(d): the OS egress budget the whole fan shares — visibility only, fail-soft.
            System.out.println("npeer-udp-bind host=" + advertised.getHostAddress()
                    + " port=" + socket.getLocalPort()
                    + " send_buffer_bytes=" + socket.getSendBufferSize()
                    + " rcvbuf_req=" + rcvBufWant + " rcvbuf_eff=" + socket.getReceiveBufferSize());
        } catch (SocketException bindRowFailure) {
            // metering only — the bind stands; the one line says the OPEN row went missing
            System.out.println("npeer-udp-bind row_failed err=" + bindRowFailure);
        }
        // The P27 boot set lands BEFORE
        // the reader thread starts (Thread.start = the happens-before edge), so the very first
        // HELLO already meets it; no live session exists yet, so no sweep is owed.
        if (bootBans != null) {
            endpoint.installBans(bootBans);
            // The persisted src= bars re-pair BEFORE the first
            // HELLO — uncharged, regardless of the knob, on the wall clock (the entries').
            System.out.println("npeer-udp-door ban_source_hold hydrated="
                    + endpoint.sourceHold.hydrate(bootBans, System.currentTimeMillis()));
        }
        if (!replay) {
            endpoint.reader.start(); // a replay door reads nothing: a stray datagram sits unread in the kernel buffer
        }
        endpoint.timer.start();
        return endpoint;
    }

    /** Endpoint-level wire recorder (survives re-admission by design; null = recording off). */
    public void attachSink(WireRecordSink sink) {
        this.sink = sink;
    }

    /**
     * The serve's paced admission cursor, or null (a door nobody wired: the smoke and contract
     * doors, which keep the legacy reader-thread seat-name spool and the plain live name fan).
     * The reader thread hands it a joiner's seat-name table ({@link #spoolSeatNameCatchUp}) and
     * the live name fan defers to it ({@link CtrlFanout#fanSeatName}); it is NOT paced when its
     * cap is 0, and then both decline and the legacy paths run.
     *
     */
    private volatile RosterAppendCatchup<PeerSession> admissionCursor;

    /** Set once by the serve right after the door opens, before any HELLO can be admitted
     *  (the reader reads it per admission). */
    public void setAdmissionCursor(RosterAppendCatchup<PeerSession> cursor) {
        this.admissionCursor = cursor;
    }

    /** The attached sink's cumulative write+flush nanos; 0 with recording off (the
     * stall-witness io_ccwr= field — the serve loop reads frame deltas). */
    public long sinkIoNanosTotal() {
        WireRecordSink s = sink;
        return s == null ? 0L : s.ioNanosTotal();
    }

    /** The per-match tick rate v3 WELCOMEs advertise (the serve loop calls
     * this once, before clients learn the port; smokes that never call it get the default 60). */
    public void setWelcomeTickRate(int tickRate) {
        if (tickRate < 10 || tickRate > 600) {
            throw new IllegalArgumentException(
                    "welcome tickRate must be in [10, 600] Hz, got: " + tickRate);
        }
        this.welcomeTickRate = tickRate;
    }

    /** The match-config flags every v5 WELCOME carries
     * (bit0 = officerSkillsAll, bit1 = clientDebug). Set by the composition root
     * beside {@link #setWelcomeTickRate}, before the port publishes — the codec never resolves
     * the value itself (platform-pure; the flags sources are the server levers
     * {@code cc.server.officerSkillsAll} / {@code cc.server.clientDebug}). */
    public void setWelcomeMatchFlags(int flags) {
        if ((flags & ~0x07) != 0) {
            throw new IllegalArgumentException("welcome matchFlags carries undefined bits: 0x"
                    + Integer.toHexString(flags)
                    + " (bits 0-2 defined: bit0 officerSkillsAll, bit1 clientDebug,"
                    + " bit2 = voice.enabled (v9); bit3 seatAdmin is PER-SEAT — minted at admit,"
                    + " never global)");
        }
        this.welcomeMatchFlags = flags;
    }

    /** W15: the custom-variant policy every v11 WELCOME carries (tier / perSeat /
     * sModCap / submissions bit) — set by the composition root beside
     * {@link #setWelcomeMatchFlags} from {@code CustomVariantConfig}, UNCONDITIONALLY (lane
     * armed or not: a player at a submissions-OFF serve sees an honest grey Submit instead of
     * a {@code custom_unavailable} reject). Domain = {@code WelcomePayload.customTailValid};
     * a breach THROWS here (boot refusal), never at the admit encode. */
    public void setWelcomeCustomConfig(int tier, int perSeat, int sModCap, boolean submissions) {
        int flags = submissions ? HandshakeCodec.CUSTOM_FLAG_SUBMISSIONS : 0;
        if (!HandshakeCodec.WelcomePayload.customTailValid(tier, perSeat, sModCap, flags)) {
            throw new IllegalArgumentException("welcome custom tail out of domain: tier=" + tier
                    + " perSeat=" + perSeat + " sModCap=" + sModCap + " submissions="
                    + submissions);
        }
        this.welcomeCustomTier = tier;
        this.welcomeCustomPerSeat = perSeat;
        this.welcomeCustomSModCap = sModCap;
        this.welcomeCustomFlags = flags;
    }

    /**
     * The serve loop's once-per-tick drain of admin {@code /wave skip|pause}
     * verbs — concatenated into the operator poll so the SAME {@code switch} serves the house
     * file, the GUI and the chat verb (one semantic). Empty when nothing was deposited.
     */
    public OperatorCommandFile.ParseResult drainAdminCommands() {
        return adminWaves.drain();
    }

    /** Review fix: deposit an admin command and, when the cap evicts an older
     * CHAT deposit, mail the evicted sender its loss (its receipt would otherwise vanish
     * silently — the door no longer sends inline acks). Reader thread; send is lock-safe. */
    void offerAdminCommand(OperatorCommandFile.Command command) {
        OperatorCommandFile.Command evicted = adminWaves.offer(command);
        if (evicted != null && evicted.receiptSessionId() != 0L) {
            sendAdminReceipt(evicted.receiptSessionId(),
                    "admin command dropped (queue full) - retry");
        }
    }

    /** The hs-13 door = the ONLY admit path since THE FLIP; null = un-armed: the door admits
     *  nobody (a stale kit still gets its ladder bounce). */
    volatile PidsHello13 hello13;

    /** Arms the version-13 door — the door's one admit path (the version-12 admit path is gone;
     *  a version-12 HELLO is a stale kit, {@link #bounceStaleHello}). Set after construct and after
     *  {@link #setAdminPassword} (the shares bind the admin secret here), before any asker. The two serve paths
     *  call it ({@code PilotServeSession}, {@code MortalDatagramHost} — row F12 pins exactly those two) beside
     *  the rows and rigs; an un-armed door answers no version-13 HELLO (dropped, counted malformed).
     *  {@code IllegalStateException}: a single-peer door, a second call, a blank door secret / an empty token
     *  mix ({@link PidsHello13}). */
    public void acceptHs13() {
        if (replay) { // admission is impossible on a replay door — the belt beside its never-started reader
            throw new IllegalStateException("hs13: a replay door admits no HELLO");
        }
        if (!multiPeer || hello13 != null) {
            throw new IllegalStateException("hs13: multi-peer door only, armed once");
        }
        hello13 = new PidsHello13(this, matchPassword);
    }

    /** The password door's team table (null on a token door). Every read or write of it holds {@link #lock}.
     * */
    public SeatTeamTable seatTeams() {
        return seatTeams;
    }

    /**
     * The serve's take of every pending team side (one lock take; no allocation when none is pending):
     * one {@code TEAM_PICK} per live, seat-holding session whose pending side is set AND whose session
     * the serve already observed live ({@code observed}, this frame's live set: the serve polls after its session
     * diff) — a session admitted after that diff keeps its deposit for the next frame; the replay applies the
     * frame's LIVE rows at the diff before the poll, so a pick is minted only for a session the reel shows live
     * at that frame. A session no
     * longer live or seated drops its deposit (the seat's vacate already reset the seat). Each command's
     * receipt session = the depositing session (the apply's holder re-check; nobody is mailed).
     *
     *
     * <p>The same gate sends a session's cross-fill tell ({@code pendingTeamNotice}) as ONE server chat line, then
     * clears it, whether or not a pick is pending (a joiner seated on a seat of its own team owes no pick); a
     * session no longer live or seated drops it. Encoded under the lock, sent after it (the lock-order rule).
     *
     */
    public OperatorCommandFile.ParseResult drainTeamPicks(java.util.function.LongPredicate observed) {
        List<OperatorCommandFile.Command> picks = null;
        List<PeerSession> told = null;
        List<byte[]> tells = null;
        synchronized (lock) {
            for (PeerSession s : sessionsById.values()) {
                if (s.pendingTeamSide < 0 && s.pendingTeamNotice == null) {
                    continue;
                }
                boolean seated = s.isLiveLocked() && !s.seatVacated && s.seatId >= 1;
                if (seated && !observed.test(s.sessionId)) {
                    continue;
                }
                int side = s.pendingTeamSide;
                String notice = s.pendingTeamNotice;
                s.pendingTeamSide = -1;
                s.pendingTeamNotice = null;
                if (seated && side >= 0) {
                    if (picks == null) {
                        picks = new ArrayList<OperatorCommandFile.Command>(2);
                    }
                    picks.add(new OperatorCommandFile.Command(OperatorCommandFile.Verb.TEAM_PICK, s.seatId, 0f,
                            false, SeatTeamLane.wordOfSide(side), s.sessionId));
                }
                if (seated && notice != null) {
                    if (told == null) {
                        told = new ArrayList<PeerSession>(2);
                        tells = new ArrayList<byte[]>(2);
                    }
                    told.add(s);
                    tells.add(ChatFrameCodec.encodeDown(s.seatId, "server", notice));
                }
            }
        }
        for (int i = 0; told != null && i < told.size(); i++) {
            sendCtrlToSession(told.get(i), tells.get(i));
        }
        return picks == null ? OperatorCommandFile.ParseResult.EMPTY
                : new OperatorCommandFile.ParseResult(List.copyOf(picks), List.of());
    }

    /**
     * Arm (or clear, {@code null}) the ADMIN credential — set after construct,
     * before serve, like {@link #setWelcomeMatchFlags}. The serve-open twin of these laws is the
     * caller's ({@code mintAdminPassword}: needs a match password, must differ from it); this door
     * refuses what it can see itself, each BEFORE the store (a refused secret is never armed):
     * a blank secret ({@code IllegalArgumentException}), a door already armed at version 13 and an
     * admin secret on a token-mode door ({@code IllegalStateException}), and an admin secret equal
     * to the match password ({@code IllegalArgumentException}, compared constant-time as the admit
     * compares it; the message never carries either secret) — equal secrets would make every
     * match-password joiner admin at both versions.
     */
    public void setAdminPassword(String adminPassword) {
        if (adminPassword != null && adminPassword.isBlank()) { // Blank = the public generator
            throw new IllegalArgumentException("admin password blank");
        }
        if (hello13 != null) {
            throw new IllegalStateException("hs13 armed: the admin secret is bound at acceptHs13");
        }
        if (adminPassword != null && passwordLedger == null) {
            throw new IllegalStateException("admin password set on a token-mode door — the admin"
                    + " credential rides the match-password slot and needs a match password"); //
        }
        if (adminPassword != null && matchPassword != null
                && cc.starsector.server.match.MatchPasswords.constantTimeEquals(adminPassword, matchPassword)) {
            throw new IllegalArgumentException("admin password equals the match password — every joiner would be"
                    + " admin"); // no dev id in the text: a host reads it
        }
        this.adminPassword = adminPassword;
    }

    /** Arm the {@code /debugships}+{@code /spawnfoe} catalogue — set after
     * construct, before serve, like {@link #setAdminPassword} (volatile, reader-thread read). */
    public void setDebugVariants(PickableVariants debugVariants) {
        this.debugVariants = debugVariants;
    }

    /** True when the mod scan can canonicalize an id. The ONE pick allowlist on every door
     * (debug included) is the curated mod scan — the debug-door widening to the
     * pilotable registry catalogue ({@code widenPicksForDebugDoor})
     * was RETIRED and DELETED
     * — never re-wire it; {@code /debugships} + {@code /spawnfoe} keep the admin
     * catalogue ({@link #setDebugVariants}). */
    // pickingAvailable() / canonicalPickOrNull() moved to SeatVariantPicks (W15 v0 step 3(c)).
    /** The door's pick surface (allowlist + joiner default + HELLO apply + the W15 own-custom
     * fallback with its 30 s hold); the wiring attaches the custom lane to it. */
    public SeatVariantPicks variantPicks() {
        return variantPicks;
    }

    // ---- W12 voice L2: the relay attachment -------------------------------

    /** The voice relay (null = voice disabled on this serve). Attached once at boot. */
    volatile VoiceRelay voice;

    public void attachVoice(VoiceRelay relay) {
        this.voice = relay;
        synchronized (lock) {
            voiceRebuildLiveLocked();
        }
    }

    public VoiceRelay voiceOrNull() {
        return voice;
    }

    /** The round id stamped on every outgoing datagram (the relay's stale-round check). */
    public int currentRoundId() {
        return roundId;
    }

    /**
     * Lock held: hand the relay a fresh live-endpoint array (copy-on-write; the relay never
     * touches this lock). Called at the four mutation sites — admit, remove, fail, BYE.
     */
    void voiceRebuildLiveLocked() {
        VoiceRelay v = voice;
        if (v == null) {
            return;
        }
        List<VoiceRelay.Endpoint> live = new ArrayList<>(sessionsById.size());
        for (PeerSession session : sessionsById.values()) {
            if (session.isLiveLocked()) {
                live.add(session.voiceEndpoint());
            }
        }
        v.setLive(live.toArray(new VoiceRelay.Endpoint[0]));
    }

    /**
     * One VCDN part on the VOICE channel — outside the reliability core's seq spaces (the
     * relay's own per-listener seq), receiver state zeroed. Thread-agnostic: the relay thread is
     * a third sender on the socket; {@link #sendToSession} keeps the per-seat TX accounting.
     */
    public void sendVoiceDatagram(PeerSession session, long voiceSeq, byte[] payload) {
        sendToSession(session, DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.VOICE,
                roundId, session.sessionId, voiceSeq, 0L, 0, 0L, payload));
    }

    public boolean isOffLoopback() {
        return offLoopback;
    }

    /**
     * The PERIMETER liveness assert, fail-closed. The socket-liveness
     * code default is {@code false} (every rig binds 127.0.0.1 and the contract rig sets it off on
     * purpose), while a serve reachable off loopback without it is the SimThreadSiegeProbe's
     * proven hang shape — so an off-loopback door with liveness OFF throws at open (the serve's
     * try-with-resources closes the socket) instead of serving unarmed; a loopback bind is exempt.
     * Pure (the contract row); the serve calls it right after the door reports its bind.
     */
    public static void requireLivenessArmed(boolean offLoopback, boolean socketLiveness) {
        if (offLoopback && !socketLiveness) {
            throw new IllegalStateException("npeer-serve socket_liveness_unarmed off_loopback=true:"
                    + " a perimeter serve must run with net.socketLiveness=true (cc-server.properties /"
                    + " -Dcc.net.socketLiveness=true / CC_NET_SOCKET_LIVENESS=true); loopback binds are exempt");
        }
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            // Endpoint teardown frees every held seat (bookkeeping only — post-close drains never
            // emit; the cause-aware vacate degrades to the plain release unarmed).
            for (PeerSession session : sessionsById.values()) {
                vacateSessionSeatLocked(session, SeatStateCause.VACATE_SILENCE);
            }
            lock.notifyAll();
        }
        socket.close(); // unblocks the reader's receive()
        reader.interrupt();
        timer.interrupt();
        if (multiPeer) {
            // Join the timer BOUNDED (≤ 500 ms)
            // so its periodic door line cannot land AFTER the final one below (never self-join:
            // a close() run on the timer thread skips the wait).
            if (Thread.currentThread() != timer) {
                try {
                    timer.join(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); // logged once: close() runs once
                    System.out.println("pilot-datagram-server close_timer_join_interrupted");
                }
            }
            // The door's FINAL counters. The timer cadence is ≤ 5 s stale, so a serve ended
            // by end-match / last-session-dead left its last HELLOs' verdicts unprinted (the
            // await_timeout line covers only the never-joined path). Legacy logs stay byte-stable.
            System.out.println(doorLine(0));
        }
        VoiceRelay v = voice; // W12 L2: the relay thread dies with the door
        if (v != null) {
            v.close();
        }
    }

    // ------------------------------------------------------------------ multi-peer surface

    /** Snapshot of the LIVE sessions (admission order) — the N serve loop's session-set view. */
    public List<PeerSession> liveSessions() {
        synchronized (lock) {
            List<PeerSession> live = new ArrayList<PeerSession>();
            for (PeerSession session : sessionsById.values()) {
                if (session.isLiveLocked()) {
                    live.add(session);
                }
            }
            return live;
        }
    }

    // ------------------------------------------------------------------ replay surface

    /** True when {@link #openReplay} opened this door. */
    public boolean isReplay() {
        return replay;
    }

    /** Datagrams this replay door swallowed instead of sending (cumulative; always 0 on a live door). */
    public long suppressedSends() {
        return suppressedSends.get();
    }

    /** The replay verbs' public surface: one-line delegates to {@link PidsReplayDoor}, where the bodies, their
     *  contracts and the replay-door-only guard live; callers outside this package keep these entry points.
     * */
    public long replayCmdNoop() {
        return replayDoor.replayCmdNoop();
    }

    public void replayStageCommandDeaths(List<ReplayFeed.CommandDeath> deaths) {
        replayDoor.replayStageCommandDeaths(deaths);
    }

    public PeerSession replayMint(long sessionId, int seat, long shipId, boolean observer, boolean admin) {
        return replayDoor.replayMint(sessionId, seat, shipId, observer, admin);
    }

    public void replayDie(long sessionId, String departureReason) {
        replayDoor.replayDie(sessionId, departureReason);
    }

    public void replayClearTenureGate(int seat) {
        replayDoor.replayClearTenureGate(seat);
    }

    /** The address a replayed session is born on ({@link PidsReplayDoor#replayMint}): this door's own bound
     *  loopback address, handed out instead of the socket, so every send still goes through
     *  {@link #sendRawDatagram} and its replay belt. Lock-free (the socket is final). */
    InetSocketAddress boundSelf() {
        return new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
    }

    /**
     * Block (up to {@code timeoutMillis}) until at least {@code n} sessions are live — the
     * multi-peer analog of the retired legacy door's {@code awaitClient(long)} (git history).
     * Returns false on a quiet timeout (with the same forensics line); throws on a transport
     * failure.
     */
    public boolean awaitLiveSessions(int n, long timeoutMillis) throws IOException {
        return awaitLiveSessions(n, timeoutMillis, () -> false);
    }

    /**
     * {@link #awaitLiveSessions(int, long)} with an {@code abort} probe evaluated OUTSIDE the
     * lock once per wait slice ({@value #WAIT_SLICE_MILLIS} ms): {@code true} ends the wait
     * early with {@code false} and NO forensics line (the caller knows why it aborted). Born of
     * a lobby-end incident: the serve loop's operator poll lives
     * at frame (a2), so an {@code end-match} written while the serve sat in this wait was never
     * read and the operator could not stop a mis-started public serve from the GUI — the probe is
     * where the serve polls the operator file during the lobby.
     */
    public boolean awaitLiveSessions(int n, long timeoutMillis, java.util.function.BooleanSupplier abort)
            throws IOException {
        if (n < 1) {
            throw new IllegalArgumentException("awaitLiveSessions needs n >= 1, got: " + n);
        }
        long deadline = nowMillis() + timeoutMillis;
        while (true) {
            if (abort.getAsBoolean()) {
                return false;
            }
            synchronized (lock) {
                if (readerFailure != null) {
                    throw readerFailure;
                }
                if (closed) {
                    throw new IOException("pilot datagram server closed while awaiting sessions");
                }
                int live = 0;
                for (PeerSession session : sessionsById.values()) {
                    // PILOTS only — an observer (the
                    // owner's second window / the filmstrip's eyes) never counts as the first
                    // client, so the match clock never starts on a spectator.
                    if (session.isLiveLocked() && !session.observer) {
                        live++;
                    }
                }
                if (live >= n) {
                    return true;
                }
                long remaining = deadline - nowMillis();
                if (remaining <= 0L) {
                    System.out.println("pilot-datagram-server await_timeout live=" + live
                            + " wanted=" + n + " received=" + readerCounters.receivedDatagrams
                            + " undecodable=" + readerCounters.droppedUndecodable
                            + " malformed_hello=" + readerCounters.droppedMalformedHello
                            + " v1_bounced=" + readerCounters.helloV1Bounced
                            + " v2_bounced=" + readerCounters.helloV2Bounced
                            + " v3_bounced=" + readerCounters.helloV3Bounced
                            + " v4_bounced=" + readerCounters.helloV4Bounced
                            + " v5_bounced=" + readerCounters.helloV5Bounced
                            + " v6_bounced=" + readerCounters.helloV6Bounced
                            + " hello_rejected=" + readerCounters.helloRejected
                            + " hello_banned=" + readerCounters.helloBanned //
                            + " hello_probed=" + readerCounters.helloProbed
                            + " probe_dropped=" + readerCounters.probeDropped
                            + " bad_session=" + readerCounters.droppedBadSession
                            + " bad_source=" + readerCounters.droppedBadSource
                            + " hello_body_refused=" + readerCounters.helloBodyRefused); // appended
                    return false;
                }
                waitOnLock(Math.min(remaining, WAIT_SLICE_MILLIS),
                        "pilot datagram server session await interrupted");
            }
        }
    }

    /**
     * The N serve loop's per-tick DOWN fan (multi-door twin of the retired
     * {@code sendSnapshot}): CTRL frames spool-or-fail in list order (GPEE/EDSP/SEAT — encoded
     * ONCE by {@link DownTickComposition} above the session loop; per-peer seq/acks live only in
     * the 32-byte envelope, so every session's same-tick payload bytes are equal), then each
     * snapshot chunk rides the latest-wins SNAP lane. Never blocks and never throws on peer
     * state: a dead or dying session is a silent no-op (the serve loop observes
     * {@link PeerSession#isLive}), and a full window spools to the timer thread
     * ({@link #CTRL_SPOOL_BOUND}; overflow = async {@code ctrl_window_stall} death — the sim
     * thread is never the casualty).
     */
    public void sendTickToSession(PeerSession session, long stampTick, List<byte[]> ctrlFrames,
            List<byte[]> snapChunks) {
        requireMultiPeerDoor("sendTickToSession");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(ctrlFrames, "ctrlFrames");
        Objects.requireNonNull(snapChunks, "snapChunks");
        synchronized (lock) {
            if (!session.isLiveLocked()) {
                return;
            }
            session.latestStampTick = stampTick;
        }
        if (session.replay) {
            return; // the stamp above stays (the take-age read uses it); a replayed session has no peer to send to
        }
        for (byte[] frame : ctrlFrames) {
            ctrlSpoolOrFail(session, frame);
        }
        for (byte[] chunk : snapChunks) {
            session.reliability.snapSend(chunk, nowMillis());
        }
    }

    /**
     * The N serve loop's sink record: ONE explicit-stamp {@code KIND_DOWN}
     * record per tick for the whole endpoint — {@code composedDownRecord} is
     * {@link DownTickComposition#composedDownRecord()}, content-identical to the bytes every
     * session was sent (selection-not-minting; mirrors the legacy door's sink truth).
     * Nuance: under a {@code cc.net.downEveryTicks} &gt; 1 cadence divisor the
     * record keeps the COMPOSED snap frames on off-ticks (record-as-composed — the v1
     * every-tick record contract holds; only the per-session SNAP sends thin).
     */
    public void recordTickDown(long stampTick, byte[] composedDownRecord) {
        requireMultiPeerDoor("recordTickDown");
        Objects.requireNonNull(composedDownRecord, "composedDownRecord");
        WireRecordSink s = sink;
        if (s != null) {
            s.record(WireRecordSink.KIND_DOWN, composedDownRecord, stampTick);
        }
    }

    /**
     * The serve loop's applied-input rows ({@link MatchRecordRows})
     * into the SAME reel, explicit stamp, the same null-safe sink read as {@link #recordTickDown}. Only a
     * row kind (3..31) is accepted: DOWN / UP keep their bytes and payloads, so a row can never be
     * mis-homed into a kind the UP / DOWN readers consume; the END trailer ({@code KIND_END}) is the
     * sink's own (written once at close), so a serve row may never claim it either.
     * Never throws on the sink side (its law).
     */
    public void recordRow(byte kind, byte[] payload, long tick) {
        requireMultiPeerDoor("recordRow");
        Objects.requireNonNull(payload, "payload");
        if (kind < MatchRecordRows.KIND_ROW_MIN || kind > MatchRecordRows.KIND_ROW_MAX) {
            throw new IllegalArgumentException("recordRow kind " + kind + " outside the row range");
        }
        if (kind == MatchRecordRows.KIND_END) {
            throw new IllegalArgumentException("recordRow kind " + kind + " not a serve row kind (the END trailer is the sink's own)");
        }
        WireRecordSink s = sink;
        if (s != null) {
            s.record(kind, payload, tick);
        }
    }

    /**
     * Operator kick: fail the
     * LIVE session claiming {@code seatId} through the ONE session-death lane
     * ({@code failSessionLocked}) — a session-RESET, not revocation: the seat vacates per the
     * death contract (RESEAT) and the SAME token re-admits (the D3 rejoin lane; durable
     * revocation v0 = edit the token out of the authority file, next serve). Returns false when
     * no live session claims the seat (the caller logs + ignores — operator verbs are fail-soft).
     * The key-less shape: the frame-time occupant (= {@code kickSeatSession(seatId, "")}); kept
     * for the rigs that kick by seat alone (endpoint smoke, observer rows, the client selftest host).
     */
    public boolean kickSeatSession(int seatId) {
        return kickSeatSession(seatId, "").kicked();
    }

    /**
     * The outcome of an operator kick.
     * {@code kicked} = a live session on the seat was failed by THIS call; {@code reason} is one of
     * the bounded tokens {@code ok} (kicked), {@code no_live_session} (no live session claims the
     * seat, or the one found died before the kill's relock) and {@code target_changed} (the seat's
     * live occupant is not the expected cid key — nothing kicked). Never player text. A replay door fails the
     * occupant only when the recording's next frame ends it for a kick ({@link #replayStageCommandDeaths}); a
     * live occupant answers {@code true} / {@code ok} either way.
     */
    public record KickOutcome(boolean kicked, String reason) {
    }

    /**
     * The occupant-bound
     * kick. {@code seatId} = the house file's seat (1..999999999, the parser's law; seat 0 holds the
     * observers and is never parsed). {@code expectedCidKeyOrEmpty} = the EXPECTED occupant's
     * {@link BanSet#cidKey} (lowercase hex, already {@link BanSet#isCidKey}-checked by the parser)
     * or {@code ""} = whoever holds the seat at frame time (the key-less house line). A non-empty
     * key is compared to the found session's own cid key in the SAME lock hold as the find (the
     * {@link #banSession} idiom): a mismatch, or a cid-less occupant, refuses
     * {@code target_changed} — a confirm minted for player A never kicks player B who took the
     * seat between the confirm and the frame. The KICKED notice and the kill stay OUTSIDE that hold, by
     * the lock order: a hand-change between the compare and the kill's relock is the
     * pre-existing narrow race. The kill acts on the SAME session object the compare passed and
     * re-checks only its liveness, so the race never redirects the kick to the new occupant — the
     * compared session is dead, the kill is a no-op and the outcome reads {@code no_live_session};
     * the lock is never widened for it.
     */
    public KickOutcome kickSeatSession(int seatId, String expectedCidKeyOrEmpty) {
        requireMultiPeerDoor("kickSeatSession");
        String expectedKey = expectedCidKeyOrEmpty == null ? "" : expectedCidKeyOrEmpty;
        PeerSession target = null;
        synchronized (lock) {
            for (PeerSession session : sessionsById.values()) {
                if (session.isLiveLocked() && session.seatId() == seatId) {
                    target = session;
                    break;
                }
            }
            // A replay re-runs the recording's
            // occupancy, so the key (which guarded the live operator against a seat change) is not compared; the
            // kick ends the occupant only when the recording's next frame ends it for a kick (staged by
            // replayStageCommandDeaths), here at the command as live did, never a frame late. Counted, live-shaped.
            //
            if (replay && target != null) {
                if ("operator_kick".equals(replayDoor.replayCommandDeaths.remove(target.sessionId))) {
                    failSessionLocked(target, new PilotPeerDisconnectedException("operator_kick",
                            "replayed operator kick (reason token: operator_kick; seat " + seatId + ")"));
                }
                replayDoor.noteReplayCmdNoopLocked("kick");
                return new KickOutcome(true, "ok");
            }
            if (target != null && !expectedKey.isEmpty() && (target.clientId == null
                    || !expectedKey.equals(BanSet.cidKey(target.clientId)))) {
                return new KickOutcome(false, "target_changed");
            }
        }
        if (target == null) {
            return new KickOutcome(false, "no_live_session");
        }
        // KICKED notice BEFORE the kill, outside the lock (core-send lock-order
        // rule — see sendKickedNoticeBurst); the kill re-checks liveness under its relock, so a
        // session that died in between is a no-op, never a double death.
        sendKickedNoticeBurst(target, HouseControlMessage.KICKED_REASON_OPERATOR_KICK);
        synchronized (lock) {
            if (target.isLiveLocked()) {
                failSessionLocked(target, new PilotPeerDisconnectedException("operator_kick",
                        "operator kicked this seat's session (reason token: operator_kick —"
                                + " O-0 house-file verb; seat " + seatId
                                + " session " + target.sessionId() + ")"));
                return new KickOutcome(true, "ok");
            }
        }
        return new KickOutcome(false, "no_live_session");
    }

    /**
     * The outcome of an operator {@code admin-cid} arm. {@code status} is one bounded token: {@code armed} (the
     * key joined the set), {@code rearmed} (it was already there; the live-holder scan still ran),
     * {@code refused_cap} (the set already holds {@link #ARMED_ADMIN_CAP} keys: unchanged, no scan),
     * {@code refused_token_mode} (a token door has no password seats), {@code bad_key} (not a cid key, or longer
     * than any v13 cid can encode, so no HELLO could match it) or {@code replay} (a replay door arms nothing).
     * {@code elevated} = the live holders THIS call elevated (0 or more). Never player text.
     *
     */
    public record ArmOutcome(String status, int elevated) {
    }

    /**
     * Arm one admin seat key ({@link BanSet#cidKey} form; the operator file's {@code admin-cid} verb). A
     * password-door pilot HELLO presenting that cid is then admitted AS ADMIN ({@link #admitLocked}) and is
     * neither counted nor capped by the per-address seat cap ({@link #admitBranchesLocked},
     * {@link #liveSeatsOfSourceLocked}); a live pilot already holding it is elevated NOW, in the same
     * {@link #lock} hold as the add, and is mailed the {@code /auth} success line
     * ({@link ChatCommands#AUTH_ELEVATED}, author {@code server}) so its client flips its admin presentation.
     * WHY the operator file is the trust root, with no admin-secret precondition: a player never reaches that
     * file, the key is the seat key the console minted for its own window, and the arm must work on a serve
     * with no admin password configured. Guards: password door only, pilot only (an observer is never
     * elevated, at the arm or at admission), and a cid-banned key is still refused by the ban seam, which runs
     * before admission. Threads: called on the SIM thread, takes only {@link #lock} (never inside a serve
     * lock), and the receipts go out AFTER the hold (the core-send lock-order rule; {@link #sendAdminReceipt}
     * takes the lock itself). Never throws: every refusal is an {@link ArmOutcome#status} token.
     *
     */
    public ArmOutcome armAdminCid(String key) {
        List<Long> elevatedIds = new ArrayList<Long>();
        String status;
        synchronized (lock) {
            if (replay) {
                // A recorded arm carries the redacted text, never a key, and a replay door admits nobody.
                replayDoor.noteReplayCmdNoopLocked("admin_cid");
                return new ArmOutcome("replay", 0);
            }
            if (passwordLedger == null) {
                return new ArmOutcome("refused_token_mode", 0);
            }
            if (!BanSet.isCidKey(key) || key.length() > 2 * Handshake13.CID_MAX) {
                return new ArmOutcome("bad_key", 0);
            }
            if (armedAdminKeys.contains(key)) {
                status = "rearmed";
            } else if (armedAdminKeys.size() >= ARMED_ADMIN_CAP) {
                return new ArmOutcome("refused_cap", 0);
            } else {
                armedAdminKeys.add(key);
                status = "armed";
            }
            for (PeerSession s : sessionsById.values()) {
                if (s.isLiveLocked() && !s.observer && !s.admin && s.clientId != null
                        && key.equals(BanSet.cidKey(s.clientId))) {
                    s.elevateAdmin();
                    elevatedIds.add(s.sessionId);
                }
            }
        }
        for (long id : elevatedIds) {
            sendAdminReceipt(id, ChatCommands.AUTH_ELEVATED);
        }
        return new ArmOutcome(status, elevatedIds.size());
    }

    /** Lock held: whether {@code clientIdOrNull}'s cid key is in the operator's armed admin set. The emptiness
     *  test runs first, so an un-armed serve builds no hex key per admission or per counted session.
     * */
    private boolean armedAdminKeyLocked(String clientIdOrNull) {
        return clientIdOrNull != null && !armedAdminKeys.isEmpty()
                && armedAdminKeys.contains(BanSet.cidKey(clientIdOrNull));
    }

    /**
     * The house-file god-mode's
     * liveness gate: true iff a LIVE, not seat-vacated session claims {@code seatId} right now
     * (the seat-vacated exclusion is a later fix), no side effect. The file guard
     * keyed on "no live hull", so a VACATED seat whose hull was still in play (grace AI, a parked
     * hull) latched god mode and the seat's next claimant inherited it; the live SESSION is the
     * owner a latch dies with (the vacate seam's {@link AdminSeatDebugLane#silentGodModeOff}
     * deposit). A counted session is not seat-vacated, so its later vacate (death,
     * kick, BYE, takeover) deposits that OFF after the caller's command: the latch still dies. Seat =
     * the file's bounded int (1..64); seat 0 would read the observers. Multi-peer only: the operator
     * switch that calls it also calls {@code kickSeatSession}; every door is multi-peer.
     */
    public boolean seatHasLiveSession(int seatId) {
        requireMultiPeerDoor("seatHasLiveSession");
        synchronized (lock) {
            for (PeerSession session : sessionsById.values()) {
                if (session.isLiveLocked() && !session.seatVacated && session.seatId() == seatId) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The P27 ban set this door's HELLO seam asks. */
    public BanSet bans() {
        return bans;
    }

    /** Swap the ban set. Boot = via {@link PidsOpenParams#bootBans}, installed by
     *  {@code openMultiPeer} BEFORE {@code reader.start()}; a later call = a rig's
     *  re-install (no sweep: live sessions are not re-checked — {@link #banKey} is the sweeping add). */
    public void installBans(BanSet loaded) {
        bans = Objects.requireNonNull(loaded, "loaded");
    }

    /** One ban call's outcome: {@code status} ∈ {@code ok}, {@code target_changed},
     *  {@code target_admin}, {@code cid_in_token_mode}, {@code admin_cap}, {@code cap},
     *  {@code bad_key}; {@code kicked} = live sessions the sweep ended; {@code added} = a NEW
     *  entry (false = it already existed — the sweep still ran). {@code held} = the
     *  source is held after it; {@code entryWritten} false = a refusal (the A1
     *  no-entry arm is superseded — an ok ban always writes its entry).
     *  {@code srcAttached} = the entry already stood ({@code added} false)
     *  and this ban paired its address with it ({@link BanSet#attachSrc}) — the set is dirty, the caller saves. */
    public record BanOutcome(String status, BanSet.Kind kind, String key, int kicked, boolean added,
            boolean held, boolean entryWritten, boolean srcAttached) {
        /** Every outcome that attaches no address (all but the ALREADY-with-a-pair ban). */
        public BanOutcome(String status, BanSet.Kind kind, String key, int kicked, boolean added, boolean held,
                boolean entryWritten) {
            this(status, kind, key, kicked, added, held, entryWritten, false);
        }
    }

    /** A seat's occupant for the operator {@code ban <seat>} verb: the LIVE session, else the
     *  most recent dead table entry on that seat ({@code live=false}, the offline case).
     *  {@code cidAuthorDigest} = the session's own digest of {@code clientId}, built once when it was admitted
     *  (the bans file's cid entry key): the offline ban keys its entry by it, so the serve thread hashes nothing
     *  a session already holds. {@code peerAddress} = the session's
     *  peer address, live or last (the offline operator ban pairs the seat's LAST address
     *  under the source-hold policy, as a chat ban pairs a live one); null when the peer is unresolved. */
    public record SeatOccupant(long sessionId, String clientId, boolean live, String cidAuthorDigest,
            java.net.InetAddress peerAddress) {}

    /** See {@link SeatOccupant}; null = nobody. */
    public SeatOccupant seatOccupantOrNull(int seatId) {
        synchronized (lock) {
            PeerSession lastDead = null;
            for (PeerSession s : sessionsById.values()) {
                if (s.seatId != seatId || s.observer) {
                    continue;
                }
                if (s.isLiveLocked()) {
                    return new SeatOccupant(s.sessionId, s.clientId, true, s.cidAuthorDigest,
                            s.peer == null ? null : s.peer.getAddress());
                }
                lastDead = s;
            }
            return lastDead == null ? null
                    : new SeatOccupant(lastDead.sessionId, lastDead.clientId, false, lastDead.cidAuthorDigest,
                            lastDead.peer == null ? null : lastDead.peer.getAddress());
        }
    }

    /**
     * The address a key-only cid ban ({@code ban-cid <key>}) pairs: the source-hold policy's answer
     * ({@link DoorSourceHold#wantedSrcOrNull}, no requester, so no self guard) for the session whose
     * {@code cidAuthorDigest} is {@code digest} — the live one, else the most recent dead table entry (a departed or
     * banned session stays in the table with its last peer). Null = no session here ever carried that id, or the
     * policy wants no hold. WHY: the id is one deletable file and the address is the bar that bites, so a ban by
     * key records the address whenever this serve saw the player, as {@code ban <seat>} does.
     *
     */
    public String holdSrcForDigestOrNull(String digest) {
        if (digest == null) {
            return null;
        }
        synchronized (lock) {
            PeerSession last = null;
            for (PeerSession s : sessionsById.values()) {
                if (digest.equals(s.cidAuthorDigest) && s.peer != null) {
                    last = s;
                    if (s.isLiveLocked()) {
                        break;
                    }
                }
            }
            return last == null ? null : sourceHold.wantedSrcOrNull(true, last.observer, last.peer.getAddress(), null);
        }
    }

    /** Visibility: the guess ledger's PASS-opened dry run for a source key (rows). */
    public boolean guessDryRunOpen(String sourceKey) {
        synchronized (lock) {
            return guessLedger.dryRunOpen(sourceKey);
        }
    }

    /**
     * Ban the LIVE session
     * {@code sessionId} by its cid or its source key. Lock take 1 re-verifies the target (live,
     * and still {@code expectedCidOrNull} when given — the dispatch-time capture, A4 TOCTOU),
     * re-checks the admin bit for a chat ({@code ADMIN}) origin (A7) and refuses a cid ban on a
     * token door (A6: the cid is unauthenticated there — use the source kind); then
     * {@link #banKey}. Serve thread (every surface deposits a Command; this is the one path).
     * The expected target is a cid KEY
     * ({@link BanSet#cidKey}), compared key-to-key — never decoded text vs the raw cid; SILENT (the
     * caller's {@code npeer-serve operator_ban} row is the one row per verb).
     * {@code requesterSessionId} = the chat deposit's admin (0 = none); A2 reads here.
     * The legacy shape: a PERMANENT ban ({@link BanTerm#PERM}).
     */
    public BanOutcome banSession(long sessionId, String expectedCidKeyOrNull, BanSet.Kind kind,
            BanSet.Origin origin, long now, long requesterSessionId) {
        return banSession(sessionId, expectedCidKeyOrNull, kind, origin, now, requesterSessionId, BanTerm.PERM);
    }

    /** + the ban's {@code term} ({@code wallNow} = the epoch ms of
     *  the add AND the term's base); a wanted hold now ALWAYS writes its carrier entry (the
     *  A1 no-entry observer arm is superseded: {@code src=} + {@code until=} on the entry ARE the bar). */
    public BanOutcome banSession(long sessionId, String expectedCidKeyOrNull, BanSet.Kind kind,
            BanSet.Origin origin, long wallNow, long requesterSessionId, BanTerm term) {
        requireMultiPeerDoor("banSession");
        String key;
        String rawName;
        String holdSrc;
        synchronized (lock) {
            PeerSession target = sessionsById.get(sessionId);
            String refusal = null;
            // A replay skips the key compare as kickSeatSession does: its occupant carries the door's own client id.
            if (target == null || !target.isLiveLocked()
                    || (expectedCidKeyOrNull != null && !replay && (target.clientId == null
                            || !expectedCidKeyOrNull.equals(BanSet.cidKey(target.clientId))))) {
                refusal = "target_changed";
            } else if (origin == BanSet.Origin.ADMIN && target.admin) {
                refusal = "target_admin";
            } else if (kind == BanSet.Kind.CID
                    && (passwordLedger == null || target.clientId == null || target.clientId.isBlank())) {
                refusal = "cid_in_token_mode";
            }
            if (refusal != null) {
                return new BanOutcome(refusal, kind, "", 0, false, false, false);
            }
            // A cid ban is keyed by the session's prebuilt digest (the bans file's key form; no hash on this thread).
            key = kind == BanSet.Kind.CID ? target.cidAuthorDigest
                    : DoorSourceBudget.sourceKey(target.peer.getAddress());
            // The hold ask under THIS take (PEER loopback bit; requester's
            // own key; a NON-zero requester that left the table ⇒ NO hold, fail closed).
            // A CID ban of EVERY origin asks — a console / file `ban <seat>`
            // records the address as a chat ban does; the policy knob governs all three.
            PeerSession by = sessionsById.get(requesterSessionId);
            holdSrc = sourceHold.wantedSrcOrNull(kind == BanSet.Kind.CID
                    && (by != null || requesterSessionId == 0L), target.observer, target.peer.getAddress(),
                    by == null ? null : by.peer.getAddress());
            // The label = THIS session's resolved SNMR
            // name, read under the door lock from the session itself — never the arbiter's per-SEAT
            // map (put-only, never cleared: a nameless successor inherited the previous occupant's
            // name as a DURABLE 5th token). "" = nameless (the sentinel) ⇒ no label.
            // An OBSERVER holds seat 0 — SeatNameRecord refuses seat < 1, so the
            // sibling callers' guard applies here too; without it a house `ban-session <observer sid>`
            // threw under the door lock ON THE SERVE THREAD and killed the match serve (smoke row
            // P-B13 F1). An observer has no seat name: "" ⇒ no label.
            // that guard now lives in resolvedSeatNameLocked — one law for every name-only caller.
            rawName = resolvedSeatNameLocked(target);
        }
        String label = rawName.isEmpty() ? null : VoiceMuteCommand.safeLabel(rawName);
        return banKey(kind, key, origin, wallNow, label, holdSrc, term);
    }

    /** The requester-less form (house, rigs) — no self-hold guard. */
    public BanOutcome banSession(long sessionId, String expectedCidKeyOrNull, BanSet.Kind kind, BanSet.Origin origin, long now) {
        return banSession(sessionId, expectedCidKeyOrNull, kind, origin, now, 0L);
    }

    /**
     * Add {@code (kind, key)} and end EVERY live match
     * (observers too — A5; an {@code ALREADY} entry still sweeps: idempotent) and
     * EVERY NOT-live match still holding its seat's reconnect hold + table key
     * ({@link #holdsVacatedSeatLocked}). Lock take 1: the add + the collect + each match's VCHK dry-run revoke
     * (A12); the KICKED reason-4 bursts OUTSIDE the lock (the {@link PidsChatVerbs#adminKick} lock-order shape);
     * lock take 2: {@code failSessionLocked} per still-live match, {@link #vacateHeldBannedLocked} per dead
     * holder AND per live match that died by take 2 (d6). Never touches the bans file ({@link BanPersistSink}).
     * A SRC ban revokes its key's dry run even with zero matches; a CID
     * ban on an offline cid revokes only a dead HOLDER's source (the ledger is keyed by source); an ADMIN-origin
     * sweep never ends an admin session, live or holding; SILENT (the caller's row is the one). A replay door
     * adds the entry and ends, evicts or vacates no session ({@code kicked} = 0).
     */
    public BanOutcome banKey(BanSet.Kind kind, String key, BanSet.Origin origin, long now) {
        return banKey(kind, key, origin, now, BanTerm.PERM);
    }

    /** The offline / cid-by-key ban with a {@code term} (an
     *  operator or SRC entry stays permanent by the {@link BanSet.Entry} law whatever the term). */
    public BanOutcome banKey(BanSet.Kind kind, String key, BanSet.Origin origin, long wallNow, BanTerm term) {
        return banKey(kind, key, origin, wallNow, null, null, term);
    }

    /** The OFFLINE operator ban with its pair — {@code holdSrcOrNull} = the
     *  dead occupant's last source key as {@link DoorSourceHold#wantedSrcOrNull} answered it (the lane asks with the
     *  {@link SeatOccupant#peerAddress()}); the entry carries {@code src=} and the hold installs, uncharged (OP). */
    public BanOutcome banKey(BanSet.Kind kind, String key, BanSet.Origin origin, long wallNow, String holdSrcOrNull,
            BanTerm term) {
        return banKey(kind, key, origin, wallNow, null, holdSrcOrNull, term);
    }

    /** {@link #banKey} + the entry's nullable ban-time
     *  seat label ({@link #banSession} only; the offline / cid-by-key paths pass none).
     *  + the hold ask, installed in lock take 1 (A2).
     *  The entry carries {@code until = term.untilFrom(wallNow)} + the hold's
     *  {@code src}; the pair takes the BARRING entry's until (entry and pair never drift). */
    private BanOutcome banKey(BanSet.Kind kind, String key, BanSet.Origin origin, long wallNow,
            String labelOrNull, String holdSrcOrNull, BanTerm term) {
        requireMultiPeerDoor("banKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(origin, "origin");
        // A re-simulated cid ban carries the key's DIGEST (the reel records a
        // ban-cid key digested): under replay it still ends the recording's banned victims at the command, but adds
        // no entry. Every cid ban key is that digest now (the bans file's key form), so a replay door adds no cid entry
        // at all (a session ban and the offline seat ban included). False on a live door: guards unchanged.
        //
        boolean replayDigest = replay && kind == BanSet.Kind.CID && OperatorCommandFile.isKeyDigest(key);
        if (!replayDigest && !BanSet.isKey(kind, key)) {
            return new BanOutcome("bad_key", kind, key == null ? "" : key, 0, false, false, false);
        }
        List<PeerSession> matched = new ArrayList<PeerSession>(); // live at take 1: the KICKED burst + the kick
        List<PeerSession> holding = new ArrayList<PeerSession>(); // Dead, still holding seat + key
        boolean added;
        boolean srcAttached;
        synchronized (lock) {
            if (kind == BanSet.Kind.CID && passwordLedger == null) {
                return new BanOutcome("cid_in_token_mode", kind, key, 0, false, false, false);
            }
            if (replayDigest) {
                return replayDoor.replayBanAtCommandLocked(kind, key, false, false, false);
            }
            BanSet.Entry prior = bans.find(kind, key, wallNow); // live only: an expired entry is absent (F2)
            if (origin == BanSet.Origin.ADMIN
                    && sourceHold.atCapForNewUnit(prior != null, holdSrcOrNull, wallNow)) {
                return new BanOutcome("admin_cap", kind, key, 0, false, false, false);
            }
            long until = term.untilFrom(wallNow);
            BanSet.AddResult result = bans.add(kind, key, wallNow, origin, labelOrNull, until, holdSrcOrNull);
            if (result == BanSet.AddResult.CAP) {
                return new BanOutcome("cap", kind, key, 0, false, false, false);
            }
            added = result == BanSet.AddResult.ADDED;
            // A ban of an id already barred pairs the address with the standing
            // entry too, so the hold installed below survives a restart (hydrate re-installs only a src= pair)
            srcAttached = result == BanSet.AddResult.ALREADY && holdSrcOrNull != null
                    && bans.attachSrc(kind, key, holdSrcOrNull, wallNow);
            boolean holdAdded = holdSrcOrNull != null
                    && sourceHold.install(holdSrcOrNull, key, prior == null ? until : prior.until(), wallNow);
            if ((holdAdded || (added && holdSrcOrNull == null)) && origin == BanSet.Origin.ADMIN) {
                sourceHold.charge();
            }
            if (kind == BanSet.Kind.SRC) {
                // exact keys: a range key revokes nothing here; the sweep below revokes each covered peer's own key
                // (the guess ledger and DoorSourceHold stay keyed by the door's exact source key)
                guessLedger.revokeDryRun(key);
            }
            // A replayed ban keeps its in-memory entry but matches no session
            // itself; replayBanAtCommandLocked ends the recording's banned victims.
            if (replay) {
                return replayDoor.replayBanAtCommandLocked(kind, key, added, holdSrcOrNull != null, true);
            }
            for (PeerSession s : sessionsById.values()) {
                if (origin == BanSet.Origin.ADMIN && s.admin) {
                    continue;
                }
                boolean live = s.isLiveLocked();
                if (!live && !holdsVacatedSeatLocked(s)) {
                    continue;
                }
                String sourceKey = DoorSourceBudget.sourceKey(s.peer.getAddress());
                // A range key sweeps every live session whose peer it covers
                boolean match = kind == BanSet.Kind.SRC
                        ? key.equals(sourceKey) || SourceRanges.covers(key, s.peer.getAddress().getAddress())
                        : s.clientId != null && !s.clientId.isBlank()
                                && key.equals(s.cidAuthorDigest);
                if (match) {
                    (live ? matched : holding).add(s);
                    guessLedger.revokeDryRun(sourceKey);
                }
            }
        }
        for (PeerSession s : matched) {
            sendKickedNoticeBurst(s, HouseControlMessage.KICKED_REASON_BANNED);
        }
        int kicked = 0;
        int[] kickedSeats = new int[matched.size()];
        int held = 0;
        int[] heldSeats = new int[matched.size() + holding.size()]; // The vacated holds to announce
        synchronized (lock) {
            for (PeerSession s : matched) {
                if (s.isLiveLocked()) {
                    // The ban
                    // VACATES the seat at once — the flag routes vacateSessionSeatLocked to
                    // vacateBanned (PARKED now, never grace_enter). The dead session then leaves the
                    // takeover-key table only (sessionsById keeps it: the dead-table occupant read,
                    // the dead-peer BYE answer), so a post-lift HELLO reads existing == null = a
                    // FRESH claim, never "hull held, same tenure".
                    s.bannedVacate = true;
                    failSessionLocked(s, new PilotPeerDisconnectedException("banned",
                            "a P27 ban ended this session (reason token: banned — kind "
                                    + BanSet.kindToken(kind) + ", origin " + BanSet.originToken(origin)
                                    + "; seat " + s.seatId + " session " + s.sessionId + ")"));
                    dropTableKeyAndStashMutesLocked(s);
                    kickedSeats[kicked++] = s.seatId;
                } else if (vacateHeldBannedLocked(s)) {
                    heldSeats[held++] = s.seatId; // It died between the two lock takes
                }
            }
            for (PeerSession s : holding) {
                if (vacateHeldBannedLocked(s)) {
                    heldSeats[held++] = s.seatId;
                }
            }
        }
        // The room hears ONE bounded line per kicked seat —
        // server-built ASCII only (never player text), fanned OUTSIDE the lock (the core-send
        // lock-order rule); the dead session is off the live set, so only the other windows get it.
        for (int i = 0; i < kicked; i++) {
            int seat = kickedSeats[i];
            fanServerChat(seat, seat >= 1 ? "seat " + seat + " banned" : "observer banned");
        }
        for (int i = 0; i < held; i++) { // A vacated hold is announced the same way (seats only)
            fanServerChat(heldSeats[i], "seat " + heldSeats[i] + " banned");
        }
        return new BanOutcome("ok", kind, key, kicked, added, holdSrcOrNull != null, true, srcAttached);
    }

    /** Lock held. Does this NOT-live session
     *  still hold its seat's reconnect hold and its table key? = a seat in grace / PARKED the
     *  operator bans OFFLINE ({@code ban <seat>} / {@code ban-cid} on the dead-table occupant), or a
     *  session that died between {@link #banKey}'s two lock takes (lane A d6). True when its seat
     *  claim was released, nobody sits on the seat, it is the seat's most recent dead occupant (the
     *  {@link #seatOccupantOrNull} read the offline verb itself made) and the table still maps its
     *  key to it — a ban of an OLDER cid on the seat never ends the CURRENT holder's grace. */
    private boolean holdsVacatedSeatLocked(PeerSession s) {
        if (seatAuthority == null || s.observer || s.seatToken == null || !s.seatVacated
                || seatAuthority.isSeated(s.seatToken)) {
            return false;
        }
        String key = sessionTableKeyLocked(s);
        SeatOccupant occ = seatOccupantOrNull(s.seatId); // the door lock re-enters
        return key != null && sessionsByToken.get(key) == s
                && occ != null && !occ.live() && occ.sessionId() == s.sessionId;
    }

    /** Lock held. The ban ends a dead session's hold the way it ends a live one —
     *  PARKED now (ONE {@code VACATE_BANNED}; from GRACE nothing is released, the hold just ends),
     *  the table key dropped (a post-lift HELLO = a FRESH claim, never "hull held") and the mute
     *  rows stashed by key. The flag also keeps a later table sweep from re-stashing over the ban
     *  stash ({@link #removeSessionLocked}). False = it no longer holds anything (re-verified under
     *  THIS lock take: a live occupant, a re-seated seat, an already dropped key) — no announce. */
    private boolean vacateHeldBannedLocked(PeerSession s) {
        if (!holdsVacatedSeatLocked(s)) {
            return false;
        }
        s.bannedVacate = true;
        seatAuthority.vacateBanned(s.seatToken);
        dropTableKeyAndStashMutesLocked(s);
        return true;
    }

    /** Lock held. The banned session leaves the takeover-key table ONLY (sessionsById keeps it: the
     *  dead-table occupant read, the dead-peer BYE answer). Its mute
     *  rows stash by key HERE, at the ban — the removeSessionLocked sweep runs inside a post-lift admit
     *  only on a FULL table or a same-seat takeover, so a token door with a free seat would otherwise
     *  miss the carry. Shared by the live kick and the held vacate. */
    void dropTableKeyAndStashMutesLocked(PeerSession s) {
        String tableKey = sessionTableKeyLocked(s);
        if (tableKey != null) {
            sessionsByToken.remove(tableKey, s);
            VoiceRelay relay = voice;
            stashMuteCarryLocked(tableKey, s.sessionId, relay == null ? null : relay.detachMutes(s.sessionId));
        }
    }

    /** A {@link #retimeKey} outcome — the entry's verdict and
     *  whether ≥ 1 hold pair took the new until. */
    public record RetimeOutcome(BanSet.RetimeResult result, boolean holdMoved) {}

    /**
     * The {@code /banned #n <term>} RETIME of a LIVE chat-origin
     * cid entry to {@code until = term.untilFrom(wallNow)} (never {@code atMillis + term}) — ONE
     * {@link #lock} take: {@link BanSet#retime} then, when RETIMED, {@link DoorSourceHold#retime} (the
     * entry and its pairs never drift). Never ADDS (absent / expired ⇒ ABSENT, operator ⇒
     * OPERATOR), never installs or charges (surprise 7), never mints a {@code src} (no re-arm).
     * Keeps banKey's take-1 guard set that applies: {@link #requireMultiPeerDoor}; the admin cap is
     * moot (no new unit). Serve thread (the BAN_CID deposit's lane); SILENT (the lane's row).
     */
    public RetimeOutcome retimeKey(String cidKey, BanTerm term, long wallNow) {
        requireMultiPeerDoor("retimeKey");
        long until = term.untilFrom(wallNow);
        synchronized (lock) {
            BanSet.RetimeResult result = bans.retime(BanSet.Kind.CID, cidKey, until, wallNow);
            return new RetimeOutcome(result, result == BanSet.RetimeResult.RETIMED && sourceHold.retime(cidKey, until));
        }
    }

    /** Lift {@code (kind, key)} (literal match). An
     *  {@code ADMIN} requester (chat) lifts admin-origin entries only (A7). Serve thread.
     *  SILENT — the caller's
     *  {@code npeer-serve operator_unban} row carries {@code requester=} + {@code reason=}.
     *  {@code wallNow} = the caller's wall clock (epoch ms),
     *  the ONE expiry predicate — an entry lapsed at {@code wallNow} but not yet swept is absent (no lift). */
    public boolean unban(BanSet.Kind kind, String key, BanSet.Origin requester, long wallNow) {
        BanSet.Entry entry = key == null ? null : bans.find(kind, key, wallNow);
        return entry != null
                && (requester == BanSet.Origin.OP || entry.origin() == BanSet.Origin.ADMIN)
                && bans.remove(kind, key);
    }

    /**
     * The N serve loop's round-boundary MRST fan (the multi-door twin of the
     * retired {@code writeMatchReset}): compose the reset frame ONCE (payload bytes are
     * peer-independent — per-peer seq/acks live only in the envelope), spool-or-fail it to every
     * live session in admission order (never blocks, never throws on peer state — a slow peer
     * spools and the timer drains; overflow is that session's async {@code ctrl_window_stall}
     * death), bump the envelope {@code roundId} ONCE for the whole boundary (the retired legacy
     * member bumped per call because it had one peer; one boundary = one bump at N), and mirror
     * the sink truth with ONE un-stamped {@code KIND_DOWN} record. Returns the fanned-session
     * count for the boundary log.
     *
     * <p><b>Ordering tripwire:</b> this frame rides the SAME per-session CTRL spool as every
     * RAPP fan ({@code ctrlSpoolOrFail}, strict FIFO) — the client's roster lane stamps RAPP
     * frames by the MRSTs drained ahead of them and defers next-round rows until this reset
     * applies. Moving the MRST to another lane, or spooling a next-round RAPP before it (the wrap's
     * seat re-point included: it rides mintRound's fan AFTER this frame), breaks
     * that ordering (see {@code PersistentPilotIntentCombatServer.fanRosterAppendChunks}).
     */
    public int fanMatchReset(MatchResetSignal signal) throws IOException {
        requireMultiPeerDoor("fanMatchReset");
        Objects.requireNonNull(signal, "signal");
        ByteArrayOutputStream cap = new ByteArrayOutputStream(MatchResetFrameCodec.frameByteCount());
        MatchResetFrameCodec.writeFrame(cap, signal);
        byte[] frame = cap.toByteArray();
        List<PeerSession> live = liveSessions();
        for (PeerSession session : live) {
            ctrlSpoolOrFail(session, frame); // outside lock (lock-order rule)
        }
        // Round barrier: roundId increments AT MRST emission — once per boundary, not per peer.
        roundId = (roundId + 1) & 0xFF;
        WireRecordSink s = sink;
        if (s != null) {
            s.record(WireRecordSink.KIND_DOWN, frame);
        }
        return live.size();
    }

    /**
     * The N serve loop's end-of-match goodbye (the multi-door sibling of the retired
     * {@code shutdownOutput}): snapshot the live set ONCE, give each session its own flush
     * bound (the armed liveness read deadline, else {@link #UNARMED_FLUSH_BOUND_MILLIS}), and run
     * every bound CONCURRENTLY in one wait loop — a session gets its BYE the moment its CTRL lane
     * is flushed (spool drained AND core acked: the N door's spool sits upstream of the core,
     * unlike the legacy blocking send) or its bound expires, then completes on the peer's BYE
     * echo (BYE-ACK) or that same bound — the fan holds the endpoint open so the timer's 15 ms
     * resend chain can outlive a lost BYE datagram ({@code close()} follows this method; leaving
     * on first send made the goodbye one-shot on the wire). The whole wrap still costs at most
     * ONE bound even if every peer is dead. A bound expiring does not throw (posture: goodbye
     * proceeds; the peer's verdict accounting distinguishes clean/unclean by what it received); a
     * session that dies or says BYE mid-flush drops out unBYEd (nothing owed). A joiner
     * admitted AFTER the snapshot rides the close-side silence path (rare, benign — the serve is
     * over). Returns the BYE count for the wrap log.
     */
    public int fanGoodbye() throws IOException {
        requireMultiPeerDoor("fanGoodbye");
        long now = nowMillis();
        LinkedHashMap<PeerSession, Long> deadlines = new LinkedHashMap<>(); // admission order
        synchronized (lock) {
            for (PeerSession session : sessionsById.values()) {
                if (session.isLiveLocked() && !session.byeSent) {
                    long bound = session.livenessReadDeadlineMillis > 0
                            ? session.livenessReadDeadlineMillis : UNARMED_FLUSH_BOUND_MILLIS;
                    deadlines.put(session, now + bound);
                }
            }
        }
        int byes = 0;
        while (!deadlines.isEmpty()) {
            // Flush state reads each core OUTSIDE the endpoint lock (the lock-order rule the
            // timer/reader threads pin; ctrlSpoolDepth takes the lock internally — re-entry-free).
            List<PeerSession> ready = new ArrayList<PeerSession>();
            now = nowMillis();
            for (java.util.Map.Entry<PeerSession, Long> entry : deadlines.entrySet()) {
                PeerSession session = entry.getKey();
                boolean flushed = session.ctrlSpoolDepth() == 0
                        && session.reliability.ctrlFlushed();
                if (flushed || now >= entry.getValue()) {
                    ready.add(session);
                }
            }
            synchronized (lock) {
                if (closed) {
                    return byes;
                }
                for (PeerSession session : ready) {
                    if (!session.isLiveLocked() || session.byeSent) {
                        continue; // died / peer said BYE mid-flush — no goodbye owed
                    }
                    session.byeSent = true;
                    sendEnvelopeOnly(session, DatagramFrameCodec.WireChannel.BYE);
                    byes++;
                }
                // A session completes on its peer's BYE echo (BYE-ACK), the peer's own BYE,
                // its bound expiring, or death — NOT on the first send: close() follows this
                // method, so leaving on send gave the timer's 15 ms resend chain ZERO lifetime
                // and made the goodbye one-shot on the wire — a single dropped BYE datagram
                // cost the joiner its whole read deadline (the exact udp_silence exit
                // this fan was built to prevent). Bound unchanged: a never-ACKing peer
                // gets its BYE at bound expiry and leaves the same instant, so the wrap still
                // costs at most ONE bound.
                final long afterSend = nowMillis();
                deadlines.entrySet().removeIf(entry -> {
                    PeerSession session = entry.getKey();
                    return !session.isLiveLocked() || session.byeAcked || session.peerSaidBye
                            || (session.byeSent && afterSend >= entry.getValue());
                });
                if (deadlines.isEmpty()) {
                    break;
                }
                waitOnLock(TIMER_GRANULARITY_MILLIS,
                        "pilot datagram server goodbye fan interrupted");
            }
        }
        return byes;
    }

    // ------------------------------------------------------------------ observability

    /** The reader-thread counter
     *  block, read-only by law outside this class (writer = the reader thread, plus the sim thread's
     *  {@code variantReceiptsDropped} bump; every meter is exported on the door line by
     *  {@link PidsReaderCounters#appendDoorTokens}). Tests and the serve lanes read fields here
     *  instead of the 62 delegating getters this replaced. */
    public PidsReaderCounters readerCounters() { return readerCounters; }

    /** L-R1: the per-BOOT life nonce every v6 WELCOME carries — accessor for
     * status surfaces/tests; the mint lives at the field (one door per boot). */
    public long serverLife() {
        return serverLife;
    }

    private long mintServerLifeNonce() {
        long life;
        do {
            life = random.nextLong();
        } while (life == 0L);
        return life;
    }

    /** Aggregate N-mode pinned-intent drops (cross-seat {@code PINT} spoofs, all sessions). */
    public long pinnedIntentDrops() { synchronized (lock) { return counters.pinnedIntentDropsTotal; } }

    /** Aggregate N-mode pinned-declaration drops (cross-seat {@code PDCL} spoofs, all sessions).
     * */
    public long pinnedDeclarationDrops() { synchronized (lock) { return counters.pinnedDeclarationDropsTotal; } }

    /** PDCLs dropped at a full per-session pending FIFO ({@link #MAX_PENDING_DECLARATIONS}; the session survives) — round line {@code pdcl_fifo_drop=}, reader PF1; never deleted (a security drop counter). */
    public long declarationsDropped() { synchronized (lock) { return counters.declarationsDropped; } }

    // ------------------------------------------------------------------ reader thread

    private void readLoop() {
        byte[] buf = new byte[RECEIVE_BUFFER_BYTES];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                socket.receive(packet);
            } catch (IOException receiveFailed) {
                if (!closed) {
                    synchronized (lock) {
                        readerFailure = receiveFailed;
                        lock.notifyAll();
                    }
                }
                return;
            }
            readerCounters.receivedDatagrams++;
            readerCounters.bytesReceivedTotal += packet.getLength();
            DatagramFrameCodec.Frame frame =
                    DatagramFrameCodec.decode(packet.getData(), packet.getLength());
            if (frame == null) {
                readerCounters.droppedUndecodable++;
                continue;
            }
            SocketAddress source = packet.getSocketAddress();
            PeerSession faultSession = null;
            boolean firstTwoWay = false;
            try {
            if (frame.channel() == DatagramFrameCodec.WireChannel.HELLO) {
                handleHello(frame, source);
                continue;
            }
            if (frame.channel() == DatagramFrameCodec.WireChannel.WELCOME) {
                readerCounters.droppedUndecodable++; // a server never receives WELCOME — spoof/reflection
                continue;
            }
            if (frame.channel() == DatagramFrameCodec.WireChannel.VCHK) {
                // W15 v3: the PRE-ADMISSION custom dry-run — the HELLO tier,
                // before any session lookup (a body-bearing frame with no session to look up).
                variantCheck.handleVariantCheck(frame, source);
                continue;
            }
            PeerSession session;
            synchronized (lock) {
                session = sessionsById.get(frame.sessionId());
                if (session == null) {
                    readerCounters.droppedBadSession++;
                    continue;
                }
                if (!source.equals(session.peer)) {
                    readerCounters.droppedBadSource++;
                    continue;
                }
                // per-seat RX: attributed once session+source validate — real inbound
                // link burden even for a pending-death session's dropped-unanswered datagrams.
                session.bytesReceived.addAndGet(packet.getLength());
                if (session.pendingDeath != null) {
                    // A FAILED session is wire-DEAD. Feeding its core would keep
                    // ACKing the peer's intents — a kicked-but-still-sending client would never
                    // observe udp_silence and never arm its re-HELLO (silence IS the death
                    // contract). BYE still lands so a dead peer's own goodbye resend
                    // chain terminates (the vacate inside is idempotent).
                    if (frame.channel() == DatagramFrameCodec.WireChannel.BYE) {
                        handleByeLocked(session);
                    } else {
                        readerCounters.droppedDeadSession++;
                    }
                    continue;
                }
                firstTwoWay = markArrivalLocked(session);
                if (frame.channel() == DatagramFrameCodec.WireChannel.BYE) {
                    handleByeLocked(session);
                    continue;
                }
            }
            // W12 voice L2: the VOICE branch, outside lock, BEFORE
            // the reliability switch — voice never touches DatagramReliability. Drops count
            // inside the relay; a CRC-valid out-of-domain VCUP throws and KILLS the session
            // (the post-admit UP law — UDP loss cannot produce that shape).
            if (frame.channel() == DatagramFrameCodec.WireChannel.VOICE) {
                VoiceRelay v = voice;
                if (v != null) {
                    try {
                        v.onVoiceUp(session.voiceEndpoint(), frame.roundId(), frame.seq(),
                                frame.payload(), nowMillis());
                    } catch (IllegalArgumentException malformed) {
                        failSession(session, new PilotPeerDisconnectedException("voice_malformed",
                                "voice frame out of domain (reason token: voice_malformed —"
                                + " post-admit UP law; " + malformed.getMessage() + ")"));
                    }
                }
                continue;
            }
            // Data channels feed the core OUTSIDE lock (lock-order rule): its delivery callbacks
            // re-take lock for deposits/queues.
            DatagramReliability.Channel channel = switch (frame.channel()) {
                case SNAP -> DatagramReliability.Channel.SNAP;
                case CTRL -> DatagramReliability.Channel.CTRL;
                case ACK -> DatagramReliability.Channel.ACK;
                default -> null; // unreachable: HELLO/WELCOME/BYE/VOICE handled above
            };
            if (channel != null) {
                faultSession = session;
                session.reliability.onDatagram(channel, frame.seq(), frame.ctrlAck(),
                        frame.ctrlAckBits(), frame.snapHiSeen(), nowMillis(),
                        frame.payload().length == 0 ? null : frame.payload());
                fanStashedSeatNames(); // the core's monitor is released here
            }
            if (firstTwoWay) {
                announceJoin(session); // B5: after the core ran, no lock held
            }
            } catch (RuntimeException fault) {
                seatNameFanStash.clear(); // a faulted session's stashed name is never fanned
                onReaderFault(faultSession, fault);
            }
        }
    }

    /**
     * Belt for the "reader thread never dies" law:
     * a per-datagram unchecked exception escaping any handler is a decode
     * FAULT, never a thread death — the datagram reader dying would leave the whole endpoint
     * deaf for every peer (the {@code readerFailure} lane covers socket IOExceptions only). The
     * admitted source, if any, is failed under the post-admit UP law (reason token
     * {@code up_decode_fault}; a CRC-valid frame that faults a handler cannot be UDP loss); an
     * unadmitted source is a counted drop. ONE stdout line, exception class + message only —
     * never wire text (the message is control-char scrubbed to keep the single-line log law).
     */
    private void onReaderFault(PeerSession session, RuntimeException fault) {
        readerCounters.readerFaults++;
        String message = String.valueOf(fault.getMessage()).replaceAll("\\p{Cntrl}+", " ");
        System.out.println("pilot-datagram-server reader_fault #" + readerCounters.readerFaults
                + " session=" + (session == null ? "-" : Long.toString(session.sessionId()))
                + " " + fault.getClass().getSimpleName() + ": " + message);
        if (session != null) {
            failSession(session, new PilotPeerDisconnectedException("up_decode_fault",
                    "pilot datagram handler fault (reason token: up_decode_fault — post-admit UP"
                            + " law): " + fault.getClass().getSimpleName() + ": " + message, fault));
        }
    }

    /** Returns true exactly once per session: on the datagram that establishes two-way flow
     * ({@code livenessJoinGracePending} cannot carry that meaning: it is
     * copied on takeover, so a half-open successor inherits a settled grace). */
    private boolean markArrivalLocked(PeerSession session) {
        session.lastValidArrivalMillis = nowMillis();
        if (session.livenessJoinGracePending) {
            // The peer's first valid in-session datagram proves it live (any channel, keepalives
            // count): drop the join grace to the steady deadline, the settleJoinGrace analog.
            session.livenessJoinGracePending = false;
        }
        if (!session.reachedTwoWay) {
            session.reachedTwoWay = true;
            readerCounters.twoWayEstablished++;
            return true;
        }
        return false;
    }

    /**
     * The owner-visible join line — fanned OUTSIDE the lock (the lock-order
     * rule, {@link #fanServerChat}) AFTER the establishing datagram fed the core, so an SNAM
     * riding that first datagram already names the line; else the {@code seat-N} fallback the
     * chat relay uses. Armed by the serve loop only ({@link #setJoinAnnounce}): the smoke lane's
     * seq-pinned captures stay byte-identical, and a dedicated leg arms it to pin the contract
     * (first two-way datagram → ONE CHTD; a flap with zero two-way → zero). A session that
     * announced nothing and never established two-way is never announced at all.
     */
    private void announceJoin(PeerSession session) {
        if (!joinAnnounce || session.observer || session.joinSuppressed) {
            return; // An observer is not a joiner (no "seat-0 joined"); nor a live reconnect
        }
        SeatNameFrameCodec.SeatNameAnnounce announce = session.seatNameAnnounce;
        String name = announce != null && !announce.name().isEmpty()
                ? announce.name() : ("seat-" + session.seatId);
        fanServerChat(session.seatId, name + " joined");
    }

    /** Arm the join line (serve loop; default OFF — see {@link #announceJoin}). */
    public void setJoinAnnounce(boolean on) {
        this.joinAnnounce = on;
    }

    /**
     * Did the seat's most recent TRUE vacate (BYE/silence/kick — never
     * displacement) come from a session that had established two-way flow? The grace lane's
     * toast gate: the sim-thread pump resolves ENTER_GRACE ≤1 tick after the vacate with no
     * session in hand, so the door remembers the dying session's flag per seat. A seat never
     * vacated answers true (fail-open: an unknown history never mutes a real disconnect).
     */
    public boolean lastVacateReachedTwoWay(int seatId) {
        synchronized (lock) {
            Boolean twoWay = lastTrueVacateTwoWayBySeat.get(seatId);
            return twoWay == null || twoWay.booleanValue();
        }
    }

    private void handleByeLocked(PeerSession session) {
        if (session.byeSent && !session.byeAcked) {
            session.byeAcked = true; // BYE-ACK = BYE echo (class doc): the peer's BYE completes ours
        }
        if (!session.peerSaidBye) {
            session.peerSaidBye = true;
            voiceRebuildLiveLocked(); // W12 L2: a BYE'd session leaves the relay's live set
            // Clean goodbye ends the occupancy — the seat is re-claimable. Vacate BEFORE the echo
            // so a peer observing its BYE-ACK can rely on the seat being free.
            vacateSessionSeatLocked(session, SeatStateCause.VACATE_BYE);
            // Echo a BYE back so the peer's goodbye resend chain terminates (its BYE-ACK).
            sendEnvelopeOnly(session, DatagramFrameCodec.WireChannel.BYE);
        }
        lock.notifyAll();
    }

    // ------------------------------------------------------------------ admission

    private void handleHello(DatagramFrameCodec.Frame frame, SocketAddress source) {
        if (!(source instanceof InetSocketAddress from)) {
            readerCounters.droppedMalformedHello++;
            return;
        }
        // THE FLIP: the dispatch. A version-13 payload at an ARMED door (acceptHs13())
        // is the ONLY admit path; every other payload is a stale kit (the ladder, version 12 included) or garbage.
        // An un-armed door admits nobody: its version-13 payloads classify as nothing and are dropped, counted.
        PidsHello13 h13 = hello13;
        if (h13 == null || !PidsHello13.isVersion13(frame.payload())) {
            bounceStaleHello(frame, from);
            return;
        }
        PeerSession admitted = null; // read by the finally (the provisioned mark)
        try {
            admitted = h13.onHello(frame.payload(), from); // takes and releases `lock` itself
            // The claim → apply clock for the
            // TENURE_APPLIED row's spool_ms (the MEASURE of the tenure gate).
            long claimNanos = System.nanoTime();
            if (admitted != null) {
                // The provisioning rides a try whose
                // finally opens the tenure gate (MatchSeatAuthority.clearPickApplyPending) — a
                // spool throw fails OPEN, never a parked seat. The gate opens right after the
                // apply (the voice sheet below is not part of the tenure).
                // `applied` tells the finally
                // whether the tenure apply ran — a spool throw before it must not open the gate
                // over the LEAVER's standing override.
                boolean applied = false;
                try {
                // The once-per-admission PSTB posture block — FIRST, so it is
                // every session's down-CTRL seq 1 (the contract the smoke/NPeer seq shifts pin);
                // it stamps the serve's own experiment tag (or none) so the client sidelog
                // + echo verdict key on the admission's server-set posture, not a local guess.
                spoolPostureBlock(admitted);
                // Catch the newcomer up on the current name table
                // (outside the admission lock — the fan family's lock-order rule).
                spoolSeatNameCatchUp(admitted);
                // W7c B2: the once-per-admission variant-catalog burst, spooled
                // here on the reader thread so it rides the session's reliable CTRL lane AHEAD of
                // the serve loop's newly-live RAPP replay in practice (the serve tick diffs live
                // sessions no sooner than its next frame; a lost race degrades to the client's
                // loud unresolvable-id fallback, never corruption).
                spoolVariantCatalogCatchUp(admitted);
                // The join-request variant pick — seeded into the /ship store (or
                // the not-pickable CHTD notice) AFTER the catalog burst, outside the lock (the
                // dispatch carve's law: the store's monitor is never nested under the endpoint
                // lock).
                applyVariantPick(admitted);
                applied = true;
                } finally {
                    // The row prints BEFORE the clear so it
                    // precedes the seat's fly_in row in every tee (K2's order check reads prints,
                    // not events); the clear itself stays the fail-open exit. The gate was armed
                    // inside the claim (SeatAdmission.authorize → claim(token, freshClaim)), so
                    // only a fresh tenure reads pending here.
                    if (seatAuthority != null && seatAuthority.pickApplyPending(admitted.seatId)) {
                        long spoolMs = (System.nanoTime() - claimNanos) / 1_000_000L;
                        System.out.println("pilot-datagram-admission TENURE_APPLIED seat=" + admitted.seatId
                                + " spool_ms=" + spoolMs);
                        // The MEASURE aggregated for the round line.
                        // Counts only tenures whose apply COMPLETED — a cleared
                        // one is tenure_clears, so INFO never reads "clears=1 (tenure_applied=1)".
                        if (applied) {
                            readerCounters.tenureApplied++;
                        }
                        if (spoolMs > readerCounters.tenureSpoolMsMax) {
                            readerCounters.tenureSpoolMsMax = spoolMs;
                        }
                        try {
                            if (!applied) {
                                // The apply never ran — drop the
                                // half-provisioned tenure mark (so it never seeds later) and clear
                                // the previous tenure's standing override (the dispatch's own leaf
                                // monitor, OUTSIDE the door lock) BEFORE the gate opens.
                                synchronized (lock) {
                                    admitted.pendingTenureApply = false;
                                }
                                readerCounters.tenureClears++;
                                if (chatCommands != null) {
                                    chatCommands.clearOverride(admitted.seatId);
                                }
                            }
                        } finally {
                            // The gate still opens on ANY exit (fail-open, never a parked seat).
                            seatAuthority.clearPickApplyPending(admitted.seatId);
                        }
                    }
                }
                // W12 voice L2: the server-dictated voice sheet (VCFG) at admit
                // — a NEW CTRL magic, hence the handshake v9 bump beside this leg.
                VoiceRelay v = voice;
                if (v != null) {
                    sendCtrlToSession(admitted, v.configFrame());
                }
            }
        } finally {
            // The admission cursor may send to this session
            // from now on (it saw headroom 0 until here, so its catch-up never landed inside the
            // variant-definition burst). Marked on EVERY exit, a throw mid-burst included: safe,
            // because the cursor's budget keys on the session's LIVE window headroom (0 while its
            // spool is non-empty), never on this mark — the mark only closes the in-burst window.
            if (admitted != null) {
                synchronized (lock) {
                    admitted.provisioned = true;
                }
            }
            // A squat victim failed
            // inside that resolve is TOLD here, outside the lock, on EVERY exit — AFTER the
            // joiner's provisioning (its seq-1 posture block + catch-up spools come first, so a
            // throw in the burst can never leave the joiner admitted-but-half-provisioned) and
            // even when the resolve or the provisioning throws (the victim is told now, never
            // on the NEXT HELLO). Empty on every non-evicting HELLO: one lock take, no send.
            drainPendingEvictNotices();
        }
    }

    /**
     * The HELLO v8 {@code variantId} tail == a {@code /ship}
     * pick made before the first fly-in, nothing more — the SAME store, monitor, canonical-id
     * law and W3-Q module-claim clear as {@code SHIP_SET}; it rides the round wrap while the seat
     * stays OCCUPIED. Lands BEFORE the tenure's first fly-in reads the
     * store because of the TENURE GATE: the fresh claim arms
     * {@code pickApplyPending} inside {@code MatchSeatAuthority.claim(token, true)} and
     * {@code seatsDueForFlyIn} / {@code deployableSeatIds} refuse the seat until
     * the provisioning's {@code finally} clears the mark after this apply — NOT because of the
     * intent order (the {@code combatReady} latch is per-seat-per-MATCH, so a re-claimed seat is
     * due on the next frame). A fresh tenure whose pick stores nothing clears the previous
     * tenure's standing override. Miss ⇒ NO seed (descriptor default) + one bounded
     * CHTD notice on the session's reliable CTRL lane. Reader thread, no lock held.
     */
    private void applyVariantPick(PeerSession session) {
        String pick;
        boolean isDefault;
        boolean freshTenure;
        synchronized (lock) {
            pick = session.pendingVariantPick;
            isDefault = session.pendingPickIsDefault;
            freshTenure = session.pendingTenureApply;
            session.pendingVariantPick = null;
            session.pendingPickIsDefault = false;
            session.pendingTenureApply = false;
        }
        // W15 v0 step 3(c): the resolve + seed + notice live in SeatVariantPicks — allowlist,
        // then the author's OWN customs (author key = customAuthorKey, the VSUB ledger's key), then a
        // 30 s hold when customs are on (the HELLO precedes the outbox by construction).
        variantPicks.applyPendingPick(session.sessionId, session.seatId, customAuthorKey(session), pick, isDefault,
                freshTenure);
    }

    /** {@link SeatVariantPicks.Notifier}: one server chat line to a session by id — resolved
     * under the lock, sent outside it (the {@link #sendVariantReceipt} shape); gone = dropped. */
    private void chatToSession(long sessionId, int seatId, String line) {
        PeerSession target;
        synchronized (lock) {
            target = sessionsById.get(sessionId);
            if (target == null || !target.isLiveLocked()) {
                return;
            }
        }
        sendCtrlToSession(target, ChatFrameCodec.encodeDown(seatId, "server", line));
    }

    /**
     * The stale-kit LADDER (re-run at every version bump):
     * a WELL-FORMED version 12..1 HELLO is a stale kit, not garbage —
     * it earns the readable version-SHAPED {@code kit_outdated} REJECT so the old client dies loud instead of
     * retrying into a silent-drop timeout. Pre-admission by construction: nothing in a stale HELLO is compared,
     * stored or logged (the ladder hands on a version, a nonce and a cookie tail, never a credential slot).
     * Garbage keeps the silent-drop posture. Classify is pure parse outside the lock; counter / log / encode
     * stay under it.
     *
     * <p>THE RUNG'S COOKIE LEG (version 12 only — the first grammar whose kit can answer a COOKIE): a stale
     * version-12 HELLO whose cookie does not verify earns ONLY the version-12 COOKIE challenge, under the
     * send-site law of the old version-12 gate (the reply is never larger than the request —
     * a 1:1 reflector, never an amplifier; a HELLO smaller than the challenge earns silence); one whose cookie
     * verifies is return-routable and earns the bounce. Tiers 11..1 predate the cookie and keep their direct
     * bounce. {@code DoorCookie} is self-synchronized; the reader thread is its one caller here.
     */
    private void bounceStaleHello(DatagramFrameCodec.Frame frame, InetSocketAddress from) {
        HandshakeBounceLadder.StaleHello stale = HandshakeBounceLadder.classifyOrNull(frame.payload());
        if (stale == null) {
            readerCounters.droppedMalformedHello++;
            return;
        }
        boolean rung = stale.version() == 12;
        if (rung) {
            long now = nowMillis();
            if (!doorCookie.verify(from, stale.cookie(), now)) {
                byte[] reply = HandshakeBounceLadder.encodeCookieChallengeV12(stale, doorCookie.mint(from, now));
                int request = DatagramFrameCodec.FRAMING_BYTES + frame.payload().length;
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                    if (reply.length > request) {
                        readerCounters.cookieUndersized++;
                        return;
                    }
                    if (stale.cookie().length != 0) {
                        readerCounters.cookieStale++;
                    } else {
                        readerCounters.cookieChallenged++;
                    }
                    sendRawDatagram(reply, from);
                }
                return;
            }
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (rung) {
                readerCounters.cookieVerified++;
            }
            countHelloBounceLocked(stale.version());
            // Sampled like every other REJECT print (the reply below is not);
            // tiers 11..1 reach it with no cookie.
            printRejectSampledLocked(DoorRejectReasons.KIT_OUTDATED,
                    () -> "pilot-datagram-admission REJECT reason=kit_outdated from="
                    + from + " (v" + stale.version() + " HELLO at the v"
                    + Handshake13.VERSION + " door)");
            sendRawDatagram(HandshakeBounceLadder.encodeReject(stale, roundId,
                    welcomeTickRate, serverLife), from);
        }
    }

    /** Per-tier bounce tallies (named volatile fields — the stats line + accessors read them
     * un-locked; increments stay under the door lock like every door counter). */
    private void countHelloBounceLocked(int staleVersion) {
        switch (staleVersion) {
            case 6 -> readerCounters.helloV6Bounced++;
            case 5 -> readerCounters.helloV5Bounced++;
            case 4 -> readerCounters.helloV4Bounced++;
            case 3 -> readerCounters.helloV3Bounced++;
            case 2 -> readerCounters.helloV2Bounced++;
            // v7..v11 had no tally of their own (they
            // fell into v1's); v1 alone keeps the default arm (the A3c smoke pins it). THE FLIP: + the 12 rung.
            case 7, 8, 9, 10, 11, 12 -> readerCounters.helloLegacyBounced++;
            // The version-13 tally (never v1's): the bounce ladder's rung
            // for a version-13 open counts here; while 13 is the door's own version no such rung exists, so it
            // reads 0.
            case 13 -> readerCounters.helloV13Bounced++;
            default -> readerCounters.helloV1Bounced++;
        }
    }

    /**
     * The content-fingerprint pre-check: when the door
     * is armed, the HELLO {@code fingerprint} must meet {@link #doorExpectations} (derived from
     * {@link #requiredDoorFingerprint}). Runs in BOTH {@code handleHello*Locked} AFTER the idempotent WELCOME resend (an
     * admitted session's retransmit already passed) but BEFORE token authorize AND before the
     * same-token pre-release — a bad-fingerprint HELLO carrying a live session's own token must
     * never vacate that session. Reasons reuse the TCP gate's strings; rejects travel as real
     * WELCOME status=REJECTED frames like the four token reasons. Authority-independent: the
     * armed check applies even on the legacy null-authority smoke door. Returns true when the
     * HELLO was rejected (caller returns).
     *
     * <p>The match term: BEFORE the armed gate (an unarmed door serves the document too) a PROBE on a door that
     * serves a match document records whether its {@code match=} term differs from the served id
     * ({@code Hs13.matchStale}; a null or blank print, an absent term and {@code none} are all stale). The armed
     * pre-admission check ignores that term ({@link DoorFingerprintExpectations#acceptsPreAdmission}); a SESSION
     * claim whose print differs from the door's ONLY in it is refused {@link DoorRejectReasons#MATCH_STALE}, still
     * before any credential compare, so this oracle tells an unproven source only that the id moved.
     *
     */
    boolean rejectedByDoorFingerprintLocked(HandshakeCodec.HelloPayload hello, InetSocketAddress from,
            PidsHello13.Hs13 hs13) {
        String presented = hello.fingerprint();
        if (hello.probe() && hs13 != null && matchDoc != null
                && !matchDoc.matchId().equals(ContentFingerprints.componentOrNull(presented, "match"))) {
            hs13.matchStale = true;
            readerCounters.probeDocStale++;
        }
        if (!doorExpectations.armed()) {
            return false;
        }
        String reason;
        // A PROBE (the game-free join page) may present the raw sidecar
        // composite; a session claim must present the id-table FOLD — the refusal at the door.
        boolean sessionClaim = !hello.probe();
        if (presented == null || presented.isBlank()) {
            reason = DoorRejectReasons.CONTENT_FINGERPRINT_REQUIRED;
        } else if (sessionClaim ? !doorExpectations.acceptsSession(presented)
                : !doorExpectations.acceptsPreAdmission(presented)) {
            reason = sessionClaim && doorExpectations.sessionStaleOnlyInMatch(presented)
                    ? DoorRejectReasons.MATCH_STALE : DoorRejectReasons.CONTENT_FINGERPRINT_MISMATCH;
        } else {
            return false;
        }
        readerCounters.helloRejected++;
        final String fpReason = reason;
        printRejectSampledLocked(fpReason, () -> "pilot-datagram-admission REJECT client="
                + PeerText.quoteCid(hello.clientId())
                + " reason=" + fpReason + " from=" + from
                + (fpReason.equals(DoorRejectReasons.CONTENT_FINGERPRINT_MISMATCH)
                        ? " " + doorExpectations.describeMismatch(presented, sessionClaim) : ""));
        sendRawDatagram(rejectedDatagram(hello.nonce(), reason), from);
        return true;
    }

    /** The door's REJECT bytes — the plaintext REJECTED grammar at
     * version 13. Since THE FLIP every caller is a version-13 flight; a stale kit's
     * REJECT is the ladder's ({@link #bounceStaleHello}), never this one. */
    private byte[] rejectedDatagram(long nonce, String reason) {
        return HandshakeCodec.WelcomePayload.encodeRejected(nonce, reason, roundId, welcomeTickRate,
                welcomeMatchFlags, serverLife, welcomeCustomTier, welcomeCustomPerSeat, welcomeCustomSModCap,
                welcomeCustomFlags);
    }

    /** The decoder's table; an engine-less door (no Main mint) refuses every custom frame
     * as malformed — an {@link IOException} like any other body fault, never a guess. */
    IdTables requireIdTables() throws IOException {
        if (idTables == null) {
            throw new IOException("variant frame refused: no id table installed on this door"
                    + " (an engine-less boot never decodes custom variants)");
        }
        return idTables;
    }

    /** The door's fingerprint expectations (ops / contract visibility). */
    public DoorFingerprintExpectations doorExpectations() {
        return doorExpectations;
    }

    /** The admission TAIL — the probe / budget / observer / join branches (the D4.4 N-peer admission;
     * carved from the version-12 door's admit method, which THE FLIP deleted). Lock
     * held. ONE caller: {@link PidsHello13}, which hands a DOOR-BUILT payload whose credential slot holds the
     * secret the wrap PROVED, never wire text, and a non-null {@code hs13}. The session key is the seat token in
     * token mode and the client-instance id in password mode (password mode resolves its seat
     * through {@link #resolvePasswordSeatLocked}); same key from ANY source = takeover of THAT session; a full
     * table after a successful claim first evicts DEAD entries, then release + reject. Returns the freshly
     * admitted session for the caller's outside-lock seat-name catch-up; null on reject.
     * */
    PeerSession admitBranchesLocked(HandshakeCodec.HelloPayload hello, InetSocketAddress from, PidsHello13.Hs13 hs13) {
        String sessionKey = passwordLedger != null ? hello.clientId() : hello.seatToken();
        PeerSession existing = sessionsByToken.get(sessionKey);
        if (hello.probe()) {
            // The door PROBE — answered (or refused) BEFORE any claim, never
            // admitted as a join; the fingerprint pre-check above already ran (frozen order).
            handleProbeLocked(hello, from, hs13);
            return null;
        }
        // The ADMISSION budget — AFTER the pure credential check (an
        // unauthenticated flood must never spend the global window: that would be a door DoS;
        // a failing credential falls through to the existing REJECT paths untouched) and BEFORE
        // any seat/session/ledger mutation. A budgeted HELLO is a SILENT drop, never a REJECT:
        // the client treats every REJECT as terminal (AdmissionRejected) but retransmits an
        // unanswered HELLO every 250 ms, so a legit joiner colliding with the gap lands on its
        // next retransmit. Keyed by source ADDRESS only — a churner re-ports per HELLO.
        // The window keeps a reserved slice only a NEVER-SEEN address may spend, so
        // a credential holder churning from known IPs cannot starve a first-time joiner.
        if (!hs13.budgetAsked && admissionCredentialPassesLocked(hello)
                && !admissionBudget.admit(from.getAddress().getHostAddress(), nowMillis())) {
            readerCounters.admissionBudgeted++;
            readerCounters.admissionBudgetedSources = admissionBudget.budgetedSources();
            return null;
        }
        // A v13 passed key's budget was asked before its open;
        // a full hs-13 nonce table = silence (counted), BEFORE any seat / ledger / session mutation.
        if (!hs13.hasRoomLocked()) {
            return null;
        }
        if (hello.observer()) {
            // OBSERVER admission — no seat, no hull, no ledger claim; the
            // credential is the match password (pure check, the probe's shape). Branches
            // AFTER the probe (a probe+observer HELLO is a probe) and the admission budget
            // (an observer admit fans SEAT catch-up too), BEFORE any seat/ledger mutation.
            return admitObserverLocked(hello, from, existing, hs13);
        }
        // The per-address LIVE-SEAT allowance (moderation.maxSeatsPerSource) — PASSWORD door only (a token
        // door's seats are handed out per token by the host), AFTER the observer branch (spectators never
        // count) and BEFORE any seat / ledger / authority mutation: a capped joiner causes no claim, no
        // takeover and no squat eviction, and reads a REJECT (a kit older than the next roll shows the raw
        // reason token and keeps retrying; the kit's own address-rule text lands with that roll).
        // A same-key takeover of its OWN session is never counted against itself; this box is exempt, and so
        // is a HELLO whose cid key the operator ARMED as admin (armAdminCid).
        //
        // The armed-key bypass. Such a HELLO is never capped here and
        // its session is never counted (liveSeatsOfSourceLocked): it IS the operator's own window, and a VPN
        // hairpin delivers both of the operator's windows from the tunnel's gateway address, which no interface
        // of this box holds, so a one-seat cap would refuse the second. An armed key already grants admin, so
        // the bypass widens nothing; SourceSeatCap stays immutable. The admit row carries cap=armed_bypass.
        boolean capApplies = passwordLedger != null && sourceSeatCap.armed()
                && !sourceSeatCap.exempt(from.getAddress());
        boolean capBypassed = capApplies && armedAdminKeyLocked(hello.clientId());
        if (capApplies && !capBypassed
                && liveSeatsOfSourceLocked(DoorSourceBudget.sourceKey(from.getAddress()), existing)
                        >= sourceSeatCap.cap()) {
            readerCounters.sourceCapRefused++;
            sendRejectLocked(hello, from, DoorRejectReasons.SOURCE_SEAT_CAP, "", hs13);
            return null;
        }
        boolean takeover = existing != null;
        // The variant pick seeds on a FRESH claim = a NEW
        // tenure only. Password mode = the ledger's CLAIMED (REJOINED keeps its seat and never
        // re-spawns); token mode = the token's first authorize, OR the return of a leaver whose
        // session was vacated (a BYE'd / silence-dead session stays in sessionsByToken, so its
        // return reads as a takeover — a live takeover is the same seat re-keyed, never a
        // re-spawn). Both modes AND "the seat holds no mounted hull": a re-claim of a HELD hull
        // (grace ON, or the grace-OFF AFK coast — mounted stays true) is the SAME tenure, so it
        // keeps a mid-tenure /ship pick and ignores the re-sent join pick (a pick changed in the
        // join UI while disconnected needs /ship). Password mode narrows the held-hull read to
        // the SAME cid returning to its OWN seat: the ledger forgets a
        // vacated cid, so a STRANGER's first-free claim can land on a seat whose hull still
        // coasts/holds — that is a NEW tenure (it never inherits the leaver's pick).
        boolean freshClaim;
        boolean hullHeld; // The mounted test alone decided
        String seatToken;
        if (passwordLedger != null) {
            PasswordSeatLedger.Claim claim = resolvePasswordSeatLocked(hello, from, existing, hs13);
            if (claim == null) {
                return null; // rejected — a REJECT WELCOME with the minted reason already went out
            }
            seatToken = claim.seat().seatToken();
            boolean claimed = claim.status() == PasswordSeatLedger.Status.CLAIMED;
            hullHeld = claimed && existing != null && existing.seatVacated
                    && existing.seatId == claim.seat().seatId()
                    && seatAuthority.seatMounted(claim.seat().seatId());
            freshClaim = claimed && !hullHeld;
        } else {
            seatToken = hello.seatToken();
            hullHeld = takeover && existing.seatVacated && seatAuthority.seatMounted(existing.seatId);
            freshClaim = !takeover || (existing.seatVacated && !hullHeld);
            if (takeover && !existing.seatVacated) {
                // The token's own credential presenting a new nonce or new source = takeover of
                // that session: pre-release its claim BEFORE re-authorizing.
                // Only the SAME token reaches this — a spoof with any other token
                // authorizes first and can never tear down someone else's session.
                vacateSessionSeatLocked(existing, SeatStateCause.VACATE_DISPLACED);
            }
        }
        long boundShipId;
        try {
            // A FRESH claim arms the tenure gate INSIDE the claim's monitor
            // step (no authorize → mark window for a sim frame to fly the old tenure's pick).
            boundShipId = SeatAdmission
                    .authorize(seatAuthority, hello.clientId(), seatToken, freshClaim)
                    .ownedShipId();
        } catch (SeatAdmission.SeatAdmissionRejected rejected) {
            if (passwordLedger != null) {
                // Skew backstop (defensive): the ledger granted a seat whose descriptor token the
                // authority still holds occupied. Unwind the grant so the two occupancy views
                // re-converge instead of skewing permanently. Unreachable when claims/vacates
                // stay 1:1 paired (the invariant this class maintains).
                passwordLedger.vacate(hello.clientId());
            }
            // An unknown TOKEN is a plain REJECT like its neighbours — the
            // source is cookie-verified and every attempt (right or wrong) was already paced by
            // the guess ledger (the verdict was recorded before this branch).
            sendRejectLocked(hello, from, rejected.reason(), "", hs13);
            return null;
        }
        if (takeover) {
            readerCounters.reAdmissions++;
            System.out.println("pilot-datagram-admission TAKEOVER client=" + PeerText.quoteCid(hello.clientId())
                    + " from=" + from + " (" + (passwordLedger != null ? "cid" : "token")
                    + "-keyed; prior session " + existing.sessionId + " torn down)");
            removeSessionLocked(existing, SeatStateCause.VACATE_DISPLACED);
        } else if (pilotSessionCountLocked() >= maxSessions) {
            // Table pressure: evict DEAD entries (BYE'd/death-classified, seats already vacated — they
            // linger only as same-key resend/takeover conveniences). Token mode never needs this (keys ⊆
            // the N descriptor tokens, same-key replaces its own entry); password cids are open-ended: a
            // departed cid's dead row would bounce every FRESH cid off a freed seat with session_table_full
            // (the quit-and-replace lane; live occupancy is what this cap bounds).
            // EXCEPT a holdsVacatedSeatLocked row — ≤ 1 per
            // UNSEATED seat (never the one just claimed) = the ban's reach + the same-cid held-hull return.
            for (PeerSession candidate : new ArrayList<PeerSession>(sessionsById.values())) {
                if (!candidate.isLiveLocked() && !holdsVacatedSeatLocked(candidate)) {
                    removeSessionLocked(candidate, SeatStateCause.VACATE_DISPLACED);
                }
            }
            if (pilotSessionCountLocked() >= maxSessions) {
                // Defensive backstop: the claim succeeded but the table is still full of LIVE
                // sessions (unreachable when maxSessions = descriptor seat count, since same-key
                // replaces its own entry and dead entries evict above).
                seatAuthority.vacate(seatToken, SeatStateCause.VACATE_DISPLACED);
                if (passwordLedger != null) {
                    passwordLedger.vacate(hello.clientId());
                }
                sendRejectLocked(hello, from, DoorRejectReasons.SESSION_TABLE_FULL, "", hs13);
                return null;
            }
        }
        int seatId = seatAuthority.authorizedSeatId(seatToken);
        if (passwordLedger != null) {
            // Fold: a DEAD entry of ANOTHER
            // cid still bound to THIS seat is a finished tenure — its owner's return must read
            // as a NEW claim (existing == null ⇒ fresh), never "hull held, same tenure" on the
            // hull THIS claimer now flies (it would inherit this claimer's pick, the
            // leak). Its seat is already vacated, so the remove releases nothing.
            for (PeerSession candidate : new ArrayList<PeerSession>(sessionsById.values())) {
                if (candidate != existing && !candidate.isLiveLocked() && candidate.seatId == seatId) {
                    removeSessionLocked(candidate, SeatStateCause.VACATE_DISPLACED);
                }
            }
        }
        PeerSession session = admitLocked(hello, from, seatToken, sessionKey, seatId, boundShipId,
                takeover ? existing : null, false, hs13);
        if (passwordLedger != null && seatTeams != null) {
            // EVERY admitted password claim (CLAIMED, REJOINED and the
            // same-cid takeover alike) whose seat now plays for the other team (a cross-fill, a restored team, a
            // switch kept across the takeover) deposits that side, so the serve's drain (after the session diff that
            // first sees this claim, before that frame's seat drain) lands a TEAM_PICK after the admin drain: the
            // takeover's queued silent clear runs first and cannot erase the team. Asked
            // ONLY for the seat this claim just admitted (an unwound backstop above can leave a stale entry for
            // an unclaimed seat). No receipt: nothing to switch yet before the first fly-in.
            int movedSide = seatTeams.movedSideOrMinusOne(seatId);
            if (movedSide >= 0) {
                session.pendingTeamSide = movedSide;
            }
            int asked = seatTeams.askedSideOrMinusOne(seatId);
            if (asked >= 0) {
                // this claim's team pick was full, so the player plays for the
                // other team (or a held seat stays on its own): the tell rides the drain's gate, once.
                session.pendingTeamNotice = seatTeams.crossFillNoticeOrNull(seatId);
                System.out.println("pilot-datagram-admission team_cross_fill seat=" + seatId + " asked=" + asked
                        + " seated=" + seatTeams.effectiveSide(seatId));
            }
        }
        if (freshClaim) {
            // Remember the RAW request for the first fly-in's announce decision;
            // no pick ⇒ the server-owned joiner default rides the SAME seed path
            // (applied post-spool, outside the lock — applyVariantPick).
            SeatVariantPicks.Requested requested = variantPicks.onFreshClaim(seatId, hello.variantId());
            if (requested != null) {
                session.pendingVariantPick = requested.pick();
                session.pendingPickIsDefault = requested.isDefault();
            }
            // A NEW tenure — the post-spool apply
            // runs the tenure law, and the seat's first fly-in waits for it (the tenure gate,
            // armed inside SeatAdmission.authorize's claim above). The
            // provisioning's finally in handleHello always clears the gate (fail-open); the
            // post-authorize reject paths release the seat, which clears it too.
            session.pendingTenureApply = true;
        }
        System.out.println("pilot-datagram-admission ADMIT client=" + PeerText.quoteCid(hello.clientId())
                + " seat=" + seatId + " ship=" + boundShipId + " session=" + session.sessionId
                + " from=" + from + " roundId=" + roundId + (capBypassed ? " cap=armed_bypass" : "")
                + (session.pendingPickIsDefault ? " joinerDefault=" + session.pendingVariantPick : "")
                + (hello.variantId().isEmpty() ? "" : " variant=" + PeerText.quote(hello.variantId())
                        + (freshClaim ? "" : hullHeld ? " (ignored: hull held, same tenure)"
                                : " (ignored: not a fresh claim)")));
        lock.notifyAll();
        return session;
    }

    /**
     * Password-mode seat resolution (lock held): verify the ONE shared match password
     * (the HELLO credential slot carries it — P5), resolve the client-instance id's seat through
     * the {@link PasswordSeatLedger}, and pre-release the authority occupancy on a same-cid
     * takeover so {@link SeatAdmission#authorize} can re-claim. Returns the admitted ledger
     * {@link PasswordSeatLedger.Claim} — its seat's token is the RESOLVED descriptor token (the
     * authority stays the ONE occupancy truth) and its status tells CLAIMED from REJOINED (the
     * fresh-claim gate) — or null when the HELLO was rejected (a REJECT WELCOME with
     * the minted reason already went out).
     *
     * <p>Ordering (frozen + one build-time refinement over the packet note): the password
     * checks run BEFORE anything touches session or seat state — a wrong-password HELLO naming a
     * live cid must never vacate that cid's session. The ledger claim then runs BEFORE the
     * takeover pre-release, so a live cid re-HELLO REJOINs onto its own seat (desired mismatch
     * logged, never fatal — P3) and a claim rejection is only ever reachable for a FRESH cid,
     * never after a live session lost its seat (the zombie state a claim-after-vacate order would
     * open). The pre-release therefore re-cycles ONLY the authority occupancy: the ledger claim
     * survives into the successor session, and the {@code seatVacated} flag keeps every downstream
     * vacate site (removeSessionLocked, death paths) a no-op for the predecessor.
     */
    private PasswordSeatLedger.Claim resolvePasswordSeatLocked(HandshakeCodec.HelloPayload hello,
            InetSocketAddress from, PeerSession existing, PidsHello13.Hs13 hs13) {
        String reason = passwordRejectReasonOrNull(hello);
        PasswordSeatLedger.Claim claim = null;
        if (reason == null) {
            // The team law places the claim (the table's javadoc); the
            // pick byte rides the version-13 context and is 0 until an F2 grammar carries one.
            int team = hs13 == null ? 0 : hs13.team;
            SeatTeamTable.Outcome placed = seatTeams.claim(hello.clientId(), hello.desiredShipId(), team);
            if (placed.full() && evictPendingSquatLocked(hello, from, placed.side())) {
                // Seat squat: the ledger was full of claims (for the asked team when
                // one was asked), at least two of which from ONE source never sent a datagram — the oldest
                // of those just left; this fresh credentialed joiner takes the freed seat.
                placed = seatTeams.claim(hello.clientId(), hello.desiredShipId(), team);
            }
            readerCounters.teamIgnored += placed.teamIgnored() ? 1 : 0;
            readerCounters.teamMemoryFull += placed.memoryFull() ? 1 : 0;
            readerCounters.teamMemoryEvicted += placed.memoryDropped();
            claim = placed.claim();
            if (!claim.admitted()) {
                reason = claim.rejectionReason();
            }
        }
        if (reason != null) {
            // A wrong PASSWORD is a plain REJECT to a
            // cookie-verified source. The compare rate is bounded BEFORE it by the guess ledger —
            // per key 250 ms after a grant, doubling per failure to an 8 s cap (≈ 0.125/s steady),
            // never reset by a PASS; never-seen keys share 16/s and streak ≥ 3 keys 8/s globally
            // (≤ ≈ 74 compares/s over any number of keys; cliff: first-timers held during a
            // fresh-key flood). A mistyped-password joiner gets its terminal REJECT on the first
            // answered try; the REJECT print is sampled (≤ 1 line/s/reason), the reply never.
            sendRejectLocked(hello, from, reason, "", hs13);
            return null;
        }
        if (claim.desiredMismatch()) {
            // P3: surfaced, never fatal — the rejoin keeps its own seat.
            System.out.println("pilot-datagram-admission rejoin_desired_mismatch client="
                    + PeerText.quoteCid(hello.clientId()) + " desired=" + hello.desiredShipId()
                    + " kept_ship=" + claim.seat().shipId() + " from=" + from);
        }
        if (existing != null && !existing.seatVacated && existing.seatToken != null) {
            // (seatToken null = a observer predecessor: nothing to pre-release — the
            // pilot successor's claim above was FRESH; the takeover below drops the session.)
            // Same-cid takeover pre-release (the lane, cid-keyed): the ledger claim
            // SURVIVES (the claim above REJOINED onto it — the cid keeps its seat); only the
            // authority occupancy re-cycles so authorize() can re-claim the resolved token. The
            // flag makes every downstream vacate site a no-op for the predecessor — the claim now
            // belongs to the successor session.
            seatAuthority.vacate(existing.seatToken, SeatStateCause.VACATE_DISPLACED);
            existing.seatVacated = true;
            // The flag makes the downstream vacate seam a no-op, so the
            // god-mode latch drop rides HERE for the takeover lane.
            queueGodModeOffLocked(existing.seatId);
        }
        return claim;
    }

    /**
     * A seat leaving the door drops its god-mode
     * latch — deposit {@link AdminSeatDebugLane#silentGodModeOff} on the admin queue (receipt
     * session 0: nobody is mailed) so the serve loop's operator switch releases the hull + the
     * latch at its next drain, exactly the chat verb's path (the lane is sim-thread-only and is
     * never called from the door). Without this the latch outlived the session and the seat's
     * NEXT claimant inherited invulnerability it could not turn off ({@code ADMIN_ONLY}).
     * Called from every seat-vacate seam: {@link #vacateSessionSeatLocked} (BYE / death / kick
     * / displacement — every {@code removeSessionLocked} caller funnels through it) and the
     * same-cid takeover pre-release in {@link #resolvePasswordSeatLocked}, which bypasses that
     * seam by design. Observers (seat 0) never deposit; a seat that never had god mode drains
     * as a no-op OFF. Lock held; the queue offer is lock-safe because a SILENT (receipt 0) offer
     * never evicts, so no eviction receipt is ever mailed under {@code lock} from here (before
     * the fix the eviction receipt's send took the reliability core's
     * monitor under {@code lock}, an ABBA path).
     */
    private void queueGodModeOffLocked(int seatId) {
        if (seatId >= 1) {
            offerAdminCommand(AdminSeatDebugLane.silentGodModeOff(seatId));
            // The /switchteams latch dies at the same seams, for the same reason —
            // the seat's NEXT claimant starts on the descriptor side (SeatTeamLane.silentClear).
            offerAdminCommand(SeatTeamLane.silentClear(seatId));
        }
    }

    /**
     * The password door's credential trio in its frozen order — blank credential / wrong
     * password / blank client-instance id (the ledger keys seats by the cid; a password-mode
     * kit always sends one — JoinGui mint / ephemeral fallback, P4; readable fail-closed, same
     * posture as the blank-credential reject). Pure: claims nothing, touches nothing; shared
     * by the join path ({@link #resolvePasswordSeatLocked}) and the probe.
     */
    private String passwordRejectReasonOrNull(HandshakeCodec.HelloPayload hello) {
        String presented = hello.seatToken();
        if (presented == null || presented.isBlank()) {
            return DoorRejectReasons.MATCH_PASSWORD_REQUIRED;
        }
        // EITHER secret opens the door (the admin one also marks the session
        // admin at mint); both compares constant-time; a wrong guess of either reads the SAME
        // reason (no oracle telling admin from match).
        String adminSecret = adminPassword;
        if (!cc.starsector.server.match.MatchPasswords.constantTimeEquals(matchPassword, presented)
                && !(adminSecret != null && cc.starsector.server.match.MatchPasswords
                        .constantTimeEquals(adminSecret, presented))) {
            return DoorRejectReasons.MATCH_PASSWORD_MISMATCH;
        }
        if (hello.clientId() == null || hello.clientId().isBlank()) {
            return DoorRejectReasons.CLIENT_ID_REQUIRED;
        }
        return null;
    }

    /**
     * How many LIVE pilot seats the source key {@code key} holds right now, for the seat allowance: a session
     * counts when it is live ({@link PeerSession#isLiveLocked}), holds a seat (not an observer, not vacated —
     * a goodbye frees the slot at once), is not {@code existing} (the same cid's own session a takeover
     * replaces), is not from an exempt address (this box's windows are free seats) and does not hold a cid
     * key the operator armed as admin (the operator's own window, wherever the tunnel delivers it from;
     * {@link #armAdminCid}). O(N) over the session table under the lock, at most once per admission attempt —
     * no stored counter to drift. Lock held.
     *
     */
    int liveSeatsOfSourceLocked(String key, PeerSession existing) {
        int held = 0;
        for (PeerSession s : sessionsById.values()) {
            if (s == existing || s.observer || s.seatVacated || !s.isLiveLocked()
                    || sourceSeatCap.exempt(s.peer.getAddress()) || armedAdminKeyLocked(s.clientId)) {
                continue;
            }
            if (key.equals(DoorSourceBudget.sourceKey(s.peer.getAddress()))) {
                held++;
            }
        }
        return held;
    }

    /** The PURE credential pre-check the admission budget gates on — password
     * mode = the frozen credential trio; token mode = a MINTED seat token (occupancy is not a
     * credential question: a live token's re-HELLO is the takeover lane). Claims nothing. */
    private boolean admissionCredentialPassesLocked(HandshakeCodec.HelloPayload hello) {
        if (passwordLedger != null) {
            return passwordRejectReasonOrNull(hello) == null;
        }
        String token = hello.seatToken();
        return seatAuthority != null && token != null && !token.isBlank()
                && seatAuthority.isMinted(token);
    }

    /** One credential outcome for the guess ledger. */
    enum CredentialVerdict {
        PASS, FAIL, NONE,
        /** A PASS by the ADMIN secret — the ledger remembers the class. */
        PASS_ADMIN;

        boolean passed() {
            return this == PASS || this == PASS_ADMIN;
        }
    }

    /** Feeds one verdict to the ledger — PASS ⇒
     * {@code recordPass} (opens the key's dry-run right + the (key, cid) lane), FAIL ⇒
     * {@code recordFail} (revokes the lane, streak + 1), NONE ⇒ nothing. Lock held. */
    void recordCredentialVerdictLocked(CredentialVerdict cv, String key, String lane, long now) {
        if (cv.passed()) {
            guessLedger.recordPass(key, lane, now, cv == CredentialVerdict.PASS_ADMIN);
            readerCounters.guessPass++;
        } else if (cv == CredentialVerdict.FAIL) {
            guessLedger.recordFail(key, lane, now);
            readerCounters.guessFail++;
        }
        readerCounters.mirrorGuess(guessLedger);
    }

    /** The ONE door REJECT-print
     * helper — classifies the answered REJECT ({@code reject_pw/fp/kit/other}), then prints at
     * most one line per second per reason via {@link #rejectLogSampler}; the next printed line of
     * a reason carries {@code suppressed=N}. {@code reason} is a server constant (never wire text);
     * {@code line} is built only when it prints. Never samples the REPLY. Lock held. */
    private void printRejectSampledLocked(String reason, java.util.function.Supplier<String> line) {
        readerCounters.countReject(reason); //
        if (rejectLogSampler.shouldPrint(reason, nowMillis())) {
            long suppressed = rejectLogSampler.takeSuppressed(reason);
            System.out.println(line.get() + (suppressed > 0L ? " suppressed=" + suppressed : ""));
        }
        readerCounters.rejectLogSuppressed = rejectLogSampler.suppressedTotal();
    }

    /** The ONE plain HELLO REJECT tail the join / probe
     * / observer / authorize / table-full / ban sites copied verbatim — {@code helloRejected++},
     * the sampled {@code pilot-datagram-admission REJECT} line (cid via {@link PeerText#quote};
     * {@code suffix} = a server constant such as {@code " (probe)"}), then the WELCOME
     * status=REJECTED reply. {@code reason} is a server constant. Lock held. */
    void sendRejectLocked(HandshakeCodec.HelloPayload hello, InetSocketAddress from,
            String reason, String suffix, PidsHello13.Hs13 hs13) {
        readerCounters.helloRejected++;
        printRejectSampledLocked(reason, () -> "pilot-datagram-admission REJECT client="
                + PeerText.quoteCid(hello.clientId()) + " reason=" + reason + " from=" + from + suffix);
        sendRawDatagram(rejectedDatagram(hello.nonce(), reason), from);
    }

    /**
     * The door PROBE — the joiner GUI's
     * pre-launch {@code /ship} list fetch. Password-mode door ONLY (a token door answers
     * {@code probe_unsupported}: its credential is a per-seat secret, the wrong shape to gate
     * a shared listing). Runs the door's EXISTING pre-claim checks in their frozen order — the
     * fingerprint pre-check already passed in the caller, then the password trio — and,
     * BEFORE any ledger/authority claim, answers the listing RAW to the source and forgets
     * the sender: no session, no seat, no spool, no state but the {@link DoorSourceBudget} LRU
     * (keyed only AFTER the password passed). Reply = the in-session LSTD frames byte-for-byte
     * ({@code ListingEmitter.shipEntries} with no marks; seatId 0, listingId
     * {@link #PROBE_LISTING_ID}) on channel
     * CTRL with sessionId 0 and seq = frame index, CAPPED at {@link #PROBE_MAX_FRAMES}.
     * Failing checks take the existing REJECT WELCOME (≤~105 B reflector, unchanged) —
     * including a door with NO pickables ({@code probe_no_pickables}: reachable + password
     * OK + picking unavailable, the GUI's "server default only"). Budget denials are SILENT
     * drops (counted), never a reply. Lock held (the budget + counters live under it).
     * A probe whose {@code match=} term is stale on a door that serves a match document also asks the
     * document budget ({@link #matchDocHeldLocked}) and, once served, gets the sealed DESC frames
     * BEFORE the mod-set and ship listings.
     */
    private void handleProbeLocked(HandshakeCodec.HelloPayload hello, InetSocketAddress from, PidsHello13.Hs13 hs13) {
        String reason;
        // Every door lists the curated mod scan under the 4-frame cap (the debug-door widening
        // + its 128-frame cap are DELETED).
        List<String> ids = probeListingIds();
        int frameCap = PROBE_MAX_FRAMES;
        if (passwordLedger == null) {
            reason = DoorRejectReasons.PROBE_UNSUPPORTED;
        } else {
            reason = passwordRejectReasonOrNull(hello);
            if (reason == null && ids.isEmpty()) {
                reason = DoorRejectReasons.PROBE_NO_PICKABLES;
            }
        }
        // A wrong
        // password is a plain REJECT. The hold on every attempt — correct guesses included — is
        // LAWFUL because the source is cookie-verified (return-routable): the guess ledger ran in
        // handleHelloMultiLocked after the resend and before this compare (per key 250 ms → 8 s
        // backoff, ≈ 0.125 compares/s steady; global 16/s first-seen + 8/s suspect windows, a
        // ceiling of ≈ 74 compares/s over any number of keys; cliff: first-timers held during a
        // fresh-key flood), so a correct guess is never answered at line rate.
        if (reason != null) {
            sendRejectLocked(hello, from, reason, " (probe)", hs13);
            return;
        }
        if (!hs13.budgetAsked) {
            long now = nowMillis();
            if (matchDocHeldLocked(hs13, from, now)) {
                return;
            }
            if (!probeBudget.admit(from.toString(), now)) {
                readerCounters.probeDropped++;
                return;
            }
        }
        List<byte[]> frames;
        try {
            // The joiner default rides as the entry's NEXT_RESPAWN mark (the codec's
            // existing override mark — no schema bump; the join window reads it as `default`).
            // Each probe row carries the ship's display name for the
            // join window's ship list; a null pickable never reaches here, its empty ids answer PROBE_NO_PICKABLES.
            // The hull id rides the same rows' targetLabel: the join combo and the browser
            // list show and search it, and only the server knows a kept build's hull.
            // The kept copies list newest first; the base-first partition stands
            frames = ListingEmitter.encodeListing(0, ListingFrameCodec.KIND_SHIP,
                    PROBE_LISTING_ID, ListingEmitter.shipEntries(ListingEmitter.keptNewestFirst(ids,
                            pickableVariants == null ? null : pickableVariants::keptAtMsOrNull), null,
                            variantPicks.joinerDefaultVariantOrNull(),
                            pickableVariants == null ? null : pickableVariants::displayNameOrNull,
                            pickableVariants == null ? null : pickableVariants::hullIdOrNull));
        } catch (RuntimeException codecLaw) {
            // Fail-closed, never fatal: a listing-law violation (the emitter THROWS by design)
            // must not kill the door's reader thread over a pre-admission nicety.
            readerCounters.probeDropped++;
            System.out.println("pilot-datagram-admission PROBE_ENCODE_FAILED src=" + from
                    + " " + codecLaw);
            return;
        }
        // The boot MOD SET rides the same reply FIRST
        // as its own KIND_MODSET listing (seq = its own frame index, same listingId; a pre-rung-2
        // kit ignores the kind) so the join page can name what the joiner lacks BEFORE a 60-s boot
        // bounces at the door. Sent before the ship frames: the collector declares the reply done
        // on the ship listing, so the set is on the wire by then. A set-less door sends nothing.
        List<byte[]> modFrames = List.of();
        int optionalCount = optionalModSet == null ? 0 : optionalModSet.size();
        if (modSet != null && (modSet.size() > 0 || optionalCount > 0)) {
            try {
                // Required rows first, then the published OPTIONAL rows (mark 27, never folded)
                modFrames = ListingEmitter.encodeListing(0, ListingFrameCodec.KIND_MODSET,
                        PROBE_LISTING_ID, ListingEmitter.modSetEntries(modSet, optionalModSet));
            } catch (RuntimeException codecLaw) {
                modFrames = List.of(); // the ship half still serves; the door's fold names the skew
                readerCounters.probeModsetEncodeFailed++; //
                System.out.println("pilot-datagram-admission PROBE_MODSET_ENCODE_FAILED src=" + from
                        + " " + codecLaw);
            }
        }
        int modSent = Math.min(modFrames.size(), PROBE_MODSET_MAX_FRAMES);
        // THIS probe's password is proven and both budgets granted, so a
        // stale term draws the match document FIRST (seq = its own chunk index, sealed under this exchange's root;
        // the kit trial-opens its player and admin roots). The collector finishes on the ship listing, so the chunks
        // are on the wire before it; a probe presenting the served id gets none. Sent only after the listing encoded.
        int docSent = 0;
        if (hs13.matchStale && matchDoc != null) {
            byte[][] doc = matchDoc.reply(hs13.root);
            for (int i = 0; i < doc.length; i++) {
                sendRawDatagram(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL,
                        roundId, 0L, i, 0L, 0, 0L, doc[i]), from);
            }
            docSent = doc.length;
            readerCounters.probeDocServed++;
        }
        for (int i = 0; i < modSent; i++) {
            sendRawDatagram(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL,
                    roundId, 0L, i, 0L, 0, 0L, modFrames.get(i)), from);
        }
        int sent = Math.min(frames.size(), frameCap);
        for (int i = 0; i < sent; i++) {
            sendRawDatagram(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL,
                    roundId, 0L, i, 0L, 0, 0L, frames.get(i)), from);
        }
        readerCounters.helloProbed++;
        System.out.println("pilot-datagram-admission PROBE client=" + PeerText.quoteCid(hello.clientId())
                + " src=" + from + " frames=" + sent + " of=" + frames.size()
                + " pickable=" + ids.size()
                + " modset_frames=" + modSent + " of=" + modFrames.size()
                + " mods=" + (modSet == null ? 0 : modSet.size())
                + " optional=" + optionalCount
                + " doc_frames=" + docSent);
    }

    /**
     * The match document's budget term, asked at most ONCE per probe, IMMEDIATELY before whichever
     * {@link #probeBudget} ask the probe reaches (the passed-key ask before the open, else the ask after it), and
     * only for a stale-term probe on a door that serves a document. True = DENIED: counted
     * {@code probe_doc_held} and the probe is dropped SILENTLY (nothing sent, the probe budget not asked, its
     * passed-key flag not set), so the kit's next try meets an open gap; never the listing alone, because the kit
     * finishes on the listing and would adopt "no document". The host's exempt addresses skip the per-key gap only.
     * Lock held.
     */
    boolean matchDocHeldLocked(PidsHello13.Hs13 hs13, InetSocketAddress from, long now) {
        if (matchDoc == null || !hs13.matchStale) {
            return false;
        }
        if (docBudget.admit(DoorSourceBudget.sourceKey(from.getAddress()), now,
                sourceSeatCap.exempt(from.getAddress()))) {
            return false;
        }
        readerCounters.probeDocHeld++;
        return true;
    }

    /** The probe listing's ids = the mod scan (canonical spelling); empty scan = no listing.
     * Public for the contract rows (the {@code debugShips} dispatch precedent — the door stays
     * the one caller that serves it). The pickable listing
     * PARTITIONED — the BASE ids first in their listing order, then the overlay's promoted ids (dev
     * promotions + kept ships) in theirs; {@code fold()} sorts the union, so this is a stable re-order of
     * ONE {@link PickableVariants#ids} snapshot (the base is immutable: {@link PickableVariants#baseHoldsFold}
     * splits it), never a new id. The probe's first 28 are base ships (when the base lists that many); a
     * kept id past the cap stays typable. */
    public List<String> probeListingIds() {
        if (pickableVariants == null) {
            return List.of();
        }
        List<String> listed = pickableVariants.ids();
        List<String> ordered = new ArrayList<>(listed.size());
        List<String> promoted = new ArrayList<>();
        for (String id : listed) {
            (pickableVariants.baseHoldsFold(id) ? ordered : promoted).add(id);
        }
        ordered.addAll(promoted);
        return List.copyOf(ordered);
    }

    /**
     * OBSERVER admission (lock held) — the owner's second window /
     * the filmstrip's ownerless eyes. Password-mode door ONLY (a token door's credential is a
     * per-seat secret — the wrong shape; {@code observer_password_mode_only}); the credential
     * is the frozen password trio ({@link #passwordRejectReasonOrNull}, the D4 guess budget
     * on a mismatch exactly as the join lane); a cid currently holding a LIVE PILOT session
     * cannot flip to observer ({@code observer_cid_in_use} — a pilot→observer takeover would
     * vacate a seat under the DISPLACED cause, whose module dismount never fires; the observer
     * launcher mints an ephemeral cid, so this is unreachable for it); a same-cid re-HELLO on
     * an observer (or dead) session is the takeover lane (the cid-keyed law); the live count
     * (this cid's own predecessor excluded) is capped at {@link #MAX_OBSERVER_SESSIONS}
     * ({@code observer_full}). The admit itself is {@link #admitLocked} with NO seat token
     * (every vacate site is already a no-op on a null token), session key = the cid (resend +
     * takeover), seat 0 / ship 0 — ids no descriptor seat can carry — and the WELCOME carries
     * {@link HandshakeCodec#WELCOME_FLAG_SEAT_OBSERVER} + boundShipId 0. Every failing check
     * takes the existing REJECT WELCOME shape. Returns the session for the caller's
     * outside-lock catch-ups (posture block, seat names, variant catalog — an observer needs
     * all of them to render), null on reject/drop.
     */
    private PeerSession admitObserverLocked(HandshakeCodec.HelloPayload hello,
            InetSocketAddress from, PeerSession existing, PidsHello13.Hs13 hs13) {
        String reason;
        if (passwordLedger == null) {
            reason = DoorRejectReasons.OBSERVER_PASSWORD_MODE_ONLY;
        } else {
            reason = passwordRejectReasonOrNull(hello);
        }
        if (reason == null && existing != null && existing.isLiveLocked() && !existing.observer) {
            reason = DoorRejectReasons.OBSERVER_CID_IN_USE;
        }
        if (reason == null) {
            // Sweep DEAD observer entries HERE — the pilot
            // table-pressure sweep (pilotSessionCountLocked() >= maxSessions) never reaches an
            // observer, and the observer launcher's ephemeral cid never same-key-takes-over its
            // own dead entry, so HELLO(observer, fresh cid)→BYE loops would grow sessionsById
            // without bound. Bounded by admissions (the admission budget), never by peer bytes;
            // the same-cid predecessor stays for the takeover lane below.
            for (PeerSession candidate : new ArrayList<PeerSession>(sessionsById.values())) {
                if (candidate != existing && candidate.observer && !candidate.isLiveLocked()) {
                    removeSessionLocked(candidate, SeatStateCause.VACATE_DISPLACED);
                }
            }
            int liveObservers = 0;
            for (PeerSession s : sessionsById.values()) {
                if (s != existing && s.observer && s.isLiveLocked()) {
                    liveObservers++;
                }
            }
            if (liveObservers >= MAX_OBSERVER_SESSIONS) {
                reason = DoorRejectReasons.OBSERVER_FULL;
            }
        }
        if (reason != null) {
            // A mismatch is a plain REJECT (paced by the guess ledger).
            sendRejectLocked(hello, from, reason, " (observer)", hs13);
            return null;
        }
        if (existing != null) {
            readerCounters.reAdmissions++;
            System.out.println("pilot-datagram-admission TAKEOVER client=" + PeerText.quoteCid(hello.clientId())
                    + " from=" + from + " (cid-keyed observer; prior session "
                    + existing.sessionId + " torn down)");
            removeSessionLocked(existing, SeatStateCause.VACATE_DISPLACED);
        }
        PeerSession session = admitLocked(hello, from, null, hello.clientId(), 0, 0L, existing,
                true, hs13);
        readerCounters.observerAdmitted++;
        System.out.println("pilot-datagram-admission OBSERVER client=" + PeerText.quoteCid(hello.clientId())
                + " session=" + session.sessionId + " from=" + from + " roundId=" + roundId);
        lock.notifyAll();
        return session;
    }

    /** The session count the {@code maxSessions} table bound measures — PILOT
     * sessions only (live or dead-but-unevicted, exactly as before). An observer holds no seat and
     * no ledger claim, so it holds no table slot either ({@link #MAX_OBSERVER_SESSIONS} is its own
     * bound); counting it here bounced the LAST legit joiner of a full-seat match off a FREE seat
     * with {@code session_table_full} (3-seat fortress + the owner's observer window = 4 entries
     * > 3 — the {@code ObserverAdmissionContractRows} O5 catch). Lock held. */
    private int pilotSessionCountLocked() {
        int pilots = 0;
        for (PeerSession s : sessionsById.values()) {
            if (!s.observer) {
                pilots++;
            }
        }
        return pilots;
    }

    /** Seat squat: the full-ledger door's glue — the victim
     *  selection (design + rejected IP:port keying + P25 age floor) is {@link PendingSquatLaw};
     *  this counts, queues the KICKED notice, logs and fails it. Lock held.
     *  {@code side} = the team whose seat must free ({@code -1} = any): only claims whose seat plays for that
     *  team are candidates.
     *  @return true when a claim was failed (the ledger has a free seat now) */
    private boolean evictPendingSquatLocked(HandshakeCodec.HelloPayload hello, InetSocketAddress from, int side) {
        long now = nowMillis();
        PendingSquatLaw.Pick pick = PendingSquatLaw.pickVictim(sessionsById.values(), now, pendingSquatMinAgeMillis,
                seat -> side < 0 || seatTeams.effectiveSide(seat) == side);
        if (pick == null) {
            return false;
        }
        PeerSession oldest = pick.victim();
        int heaviestCount = pick.sourcePending();
        long ageMillis = now - oldest.pendingSinceMillis;
        readerCounters.pendingEvicted++;
        pendingEvictNotices.addLast(oldest); // wq P25: told after the kill, outside the lock
        System.out.println("pilot-datagram-admission EVICT_PENDING session=" + oldest.sessionId
                + " seat=" + oldest.seatId + " from=" + oldest.peer + " pending_ms=" + ageMillis
                + " source_pending=" + heaviestCount + " for=" + PeerText.quoteCid(hello.clientId())
                + " joiner_from=" + from);
        failSessionLocked(oldest, new PilotPeerDisconnectedException("pending_evicted",
                "pilot peer claimed a seat but never began exchanging (" + ageMillis
                        + " ms; " + heaviestCount + " such claims from its source key) and a fresh joiner"
                        + " needed the seat under a full ledger; reason token: pending_evicted"));
        return true;
    }

    /** wq P25: contract rows / rigs shrink the squat age floor (the product
     *  default is {@link #PENDING_SQUAT_MIN_AGE_MILLIS}); {@code 0} = the pre-floor law. */
    public void setPendingSquatMinAgeMillis(long millis) {
        if (millis < 0L) {
            throw new IllegalArgumentException("pending-squat age floor must be >= 0, got " + millis);
        }
        pendingSquatMinAgeMillis = millis;
    }

    /** wq P25: mail every queued squat victim its KICKED notice — called on the
     *  reader thread in {@link #handleHello}'s {@code finally}: after the locked resolve that
     *  failed it AND the joiner's provisioning, on every exit; each burst is the
     *  {@link #sendKickedNoticeBurst} shape (core seq + two raw copies) on the now-dead session,
     *  which gets no other CTRL ever again, so the seq still delivers in order. The victim may
     *  already be swept out of {@code sessionsById} (the joiner's own admit sweeps dead entries)
     *  — the burst needs only the object: its core is never disposed, its peer address stands. */
    private void drainPendingEvictNotices() {
        while (true) {
            PeerSession victim;
            synchronized (lock) {
                victim = pendingEvictNotices.pollFirst();
            }
            if (victim == null) {
                return;
            }
            sendKickedNoticeBurst(victim, HouseControlMessage.KICKED_REASON_PENDING_EVICTED);
        }
    }

    /** Mint + register the fresh session (lock held). {@code sessionKey} is the takeover/resend
     * table key — the seat token in token mode, the client-instance id in password mode (
     * {@code seatToken} is then the RESOLVED descriptor token). {@code predecessor} non-null on
     * displacement/takeover: the gate-fed liveness/stamp state copies old→new (exactly the legacy
     * endpoint-level fields' survival across re-admission — A5/A6 equivalence).
     * {@code observer}: the session is an OBSERVER — seat token null, seat 0 / ship 0,
     * WELCOME bit4 set. */
    private PeerSession admitLocked(HandshakeCodec.HelloPayload hello, InetSocketAddress from, String seatToken,
            String sessionKey, int seatId, long shipId, PeerSession predecessor, boolean observer,
            PidsHello13.Hs13 hs13) {
        long minted;
        do {
            minted = random.nextLong();
        } while (minted == 0L || sessionsById.containsKey(minted));
        // 0 is the no-session sentinel (and the HELLO/WELCOME header value)
        // admin = THIS HELLO presented the admin credential, OR (a pilot only) its cid key
        // is in the operator's armed admin set (armAdminCid) — password mode only: token mode has no admin
        // secret and refuses the arm. Per-seat WELCOME bit3 rides this ONE admit encode (the REJECT sites
        // keep the match-global flags); a takeover/rejoin re-derives it from its own HELLO and the set, never
        // from the predecessor; a cid-banned key never gets here (the ban seam runs before admission).
        // The armed half, read under the lock the arm takes.
        String adminSecret = adminPassword;
        boolean secretAdmin = passwordLedger != null && adminSecret != null
                && cc.starsector.server.match.MatchPasswords.constantTimeEquals(adminSecret, hello.seatToken());
        boolean armedAdmin = !secretAdmin && passwordLedger != null && !observer
                && armedAdminKeyLocked(hello.clientId());
        boolean admin = secretAdmin || armedAdmin;
        // The SEALED WELCOME13 — since THE FLIP the only admit
        // encode (no plaintext ADMITTED leaves this door)
        int flags = welcomeMatchFlags | (admin ? HandshakeCodec.WELCOME_FLAG_SEAT_ADMIN : 0)
                | (observer ? HandshakeCodec.WELCOME_FLAG_SEAT_OBSERVER : 0);
        byte[] welcome = hs13.sealWelcome(hello.nonce(), minted, roundId, shipId, welcomeTickRate, flags,
                serverLife, welcomeCustomTier, welcomeCustomPerSeat, welcomeCustomSModCap, welcomeCustomFlags);
        PeerSession session = new PeerSession(minted, hello.nonce(), welcome, from,
                hello.clientId(), seatToken, seatId, shipId, admin, observer, false);
        if (admin) {
            System.out.println("pilot-datagram-admission ADMIN client=" + PeerText.quoteCid(hello.clientId())
                    + " seat=" + seatId + " session=" + minted + " from=" + from);
        }
        if (armedAdmin) {
            System.out.println("pilot-datagram-admission admin_armed_cid seat=" + seatId + " session=" + minted);
        }
        session.pendingSinceMillis = nowMillis(); // A fresh claim's never-two-way age starts now
        if (predecessor != null) {
            session.copyGateStateFrom(predecessor);
            remapCarriedMutesLocked(predecessor.sessionId, minted); // even with no own carry: OTHER stashes may name it
        }
        session.lastValidArrivalMillis = nowMillis();
        sessionsById.put(minted, session);
        // The hs-13 nonce table — this nonce → this session
        hs13.onAdmittedLocked(session);
        session.hs13AuthShare = hs13.auth.share(); // the YbAuth share beside the root
        voiceRebuildLiveLocked(); // W12 L2: admit = live-set mutation site 1 of 4
        if (predecessor != null && predecessor.carriedMutes != null) {
            // A same-occupant takeover keeps its mute rows BOTH ways (what it
            // muted, who muted it). The table key IS the occupant identity, so a different
            // occupant on the same seat arrives with no predecessor and inherits nothing.
            VoiceRelay relay = voice;
            if (relay != null) {
                attachMuteCarryLocked(relay, minted, predecessor.carriedMutes);
            }
            predecessor.carriedMutes = null;
            carriedMutesByKey.remove(sessionKey); // the predecessor's own by-key stash: attached once, above
        } else if (sessionKey != null) {
            VoiceRelay.MuteCarry carry = carriedMutesByKey.remove(sessionKey);
            VoiceRelay relay = voice;
            if (carry != null && relay != null) {
                remapCarriedMutesLocked(carry.ownerId(), minted);
                if (!carry.isTombstone()) { // A tombstone only remaps its id in the OTHER stashes
                    VoiceRelay.Attached att = attachMuteCarryLocked(relay, minted, carry);
                    muteCarriesByKey++;
                    System.out.println("pilot-datagram-admission MUTE-CARRY " + (passwordLedger != null ? "cid" : "token")
                            + "-keyed session=" + minted + " attached_targets=" + att.targets()
                            + " attached_by=" + att.mutedBy() + " skipped_by=" + att.skippedBy());
                }
            }
        }
        if (sessionKey != null) {
            sessionsByToken.put(sessionKey, session);
        }
        sendToSession(session, welcome);
        return session;
    }

    /** The custom-variant AUTHOR key (VSUB ledger,
     * held picks, reclaim, {@code /customs}, {@code /ship}, operator {@code custom-reject}). Password
     * mode = the client-instance id's one-way digest ({@link PeerSession#cidAuthorDigest}, {@code h1:} + 32
     * hex; the cid is bound by the password ledger, and the digest keeps that seat key off the match record).
     * Token mode = a NON-SECRET handle of
     * the seat token, {@code seat#<seatId>} (ASCII, seatId >= 1: one token = one seat for the serve, so
     * a same-token takeover keeps it) — the HELLO cid is unauthenticated there (the BAN seam's words), a
     * cid key let one seat spend another's quota or rotate cids for more; never the token itself
     * ({@code rejectSeat} logs the key). The legacy null-authority door keys on the cid's digest too. Final
     * fields only: the digest is built once per session, never per call (sim-thread readers). */
    String customAuthorKey(PeerSession s) {
        return passwordLedger != null || seatAuthority == null ? s.cidAuthorDigest : "seat#" + s.seatId;
    }

    /** The author digest of one client id = {@link CidDigest#of} (the
     *  one copy of the shape and its domain tag; the same digest keys a player ban in the bans file). A null
     *  clientId digests the empty string (a key is never null). Pure and deterministic: the same cid yields the
     *  same key on every session and every serve. Called once per {@link PeerSession} construction (the reader
     *  thread's admit, the replay mint).
     * */
    static String authorDigestOf(String clientId) {
        return CidDigest.of(clientId);
    }

    /** Lock held — rewrite session id {@code from} to its re-minted {@code to} in every stashed by-key carry (order kept), so a muter / target who reconnects while the other party's carry is stashed is still found at that party's admit. */
    private void remapCarriedMutesLocked(long from, long to) {
        carriedMutesByKey.replaceAll((key, carry) -> carry.remap(from, to));
    }

    /** Lock held: stash {@code carry} under {@code key}; returns what was stashed (null =
     *  nothing). A NULL carry (the detach found no rows) whose session id some stashed carry still NAMES is stashed as
     *  a {@link VoiceRelay.MuteCarry#tombstone}, so the id's next fresh admit still consumes it and
     *  {@link #remapCarriedMutesLocked remaps} the id in those stashes. Why: a muter / target whose counterparty
     *  dropped FIRST detaches nothing, so its own sweep (table pressure, a same-seat other-cid reclaim, the observer
     *  sweep) or ban stashed nothing, its fresh rejoin never remapped, and the counterparty's lift-return attached a
     *  dead id ({@code skipped_by=1}, indistinguishable from a muter who left). Shared by the ban stash and the table
     *  drop; same 64-key cap (a tombstone is one key, consumed at the id's admit like any carry). */
    private VoiceRelay.MuteCarry stashMuteCarryLocked(String key, long sessionId, VoiceRelay.MuteCarry carry) {
        if (carry == null && stashNamesLocked(sessionId)) {
            carry = VoiceRelay.MuteCarry.tombstone(sessionId);
        }
        if (carry != null) {
            carriedMutesByKey.put(key, carry);
        }
        return carry;
    }

    /** Lock held: is {@code id} a session the door still holds un-banned (a BYE'd-but-tabled one counts — PDES B-VAC-M4)? */
    private boolean muteHeldLocked(long id) {
        PeerSession p = sessionsById.get(id);
        return p != null && !p.bannedVacate;
    }

    /**
     * Lock held: attach {@code carry} to {@code minted}. A relation whose OTHER
     * party the door no longer holds is handed to that party's own stash (its carry or tombstone, found by owner id)
     * instead of dying with this carry, and an away target never enters a live row THROUGH AN ATTACH (nothing would ever
     * remap it there; a {@code /mute} toggle cannot write one either — {@code VoiceRelay.toggleMute} refuses a non-live id).
     * Why: seat 1 banned, the witness swept, seat 1 back FIRST — the attach skipped the away muter and wrote the
     * witness's dead id into seat 1's row, the carry was consumed, and the witness's later admit remapped stashes only:
     * both mutes were lost (PDES B-VAC-M7). An away party with no stash (cap-evicted, or the legacy keyless door) drops
     * the relation, as before. Event-driven, ≤ {@link #MUTE_CARRY_KEY_CAP} entries per away id.
     */
    private VoiceRelay.Attached attachMuteCarryLocked(VoiceRelay relay, long minted, VoiceRelay.MuteCarry carry) {
        for (long target : carry.mutedTargets()) {
            if (!muteHeldLocked(target)) { // minted mutes an away target: the target's stash is "muted by" minted
                carriedMutesByKey.replaceAll((key, c) -> c.ownerId() == target ? c.withRelation(minted, false) : c);
            }
        }
        for (long muter : carry.mutedBy()) {
            if (!muteHeldLocked(muter)) { // an away muter mutes minted: the muter's stash "mutes" minted
                carriedMutesByKey.replaceAll((key, c) -> c.ownerId() == muter ? c.withRelation(minted, true) : c);
            }
        }
        return relay.attachMutes(minted, carry.heldTargetsOnly(this::muteHeldLocked), this::muteHeldLocked);
    }

    /** Lock held: does any stashed carry name {@code id} as a target or a muter? */
    private boolean stashNamesLocked(long id) {
        for (VoiceRelay.MuteCarry carry : carriedMutesByKey.values()) {
            if (carry.mutedTargets().contains(id) || carry.mutedBy().contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** The session's takeover/resend table key: the client-instance id in password
     * mode, the seat token otherwise (null only on the legacy null-authority smoke door). */
    private String sessionTableKeyLocked(PeerSession session) {
        return passwordLedger != null ? session.clientId : session.seatToken;
    }

    /** Lock held: release the session's claim (idempotent, cause-aware) + drop it from the
     * tables. The replaced object is never mutated further — identity-final, replace-never-mutate. */
    void removeSessionLocked(PeerSession session, SeatStateCause cause) {
        vacateSessionSeatLocked(session, cause);
        sessionsById.remove(session.sessionId);
        voiceRebuildLiveLocked(); // W12 L2: remove = live-set mutation site 2 of 4
        VoiceRelay relay = voice;
        if (relay != null) {
            // The table drop is the mute purge — lifted onto the dead object so
            // a same-key takeover (the caller's next admitLocked) can re-attach them.
            session.carriedMutes = relay.detachMutes(session.sessionId);
        }
        String sessionKey = sessionTableKeyLocked(session);
        if (sessionKey != null) {
            sessionsByToken.remove(sessionKey, session);
            if (!session.bannedVacate) { // a banned session stashed at the ban (R)
                // Outlives the dead object; a row-less session named by another stash = a tombstone (F2)
                session.carriedMutes = stashMuteCarryLocked(sessionKey, session.sessionId, session.carriedMutes);
            }
        }
    }

    // ------------------------------------------------------------------ reliability callbacks
    // (reader thread, under the owning core's monitor; may take lock — never the reverse — and
    // NEVER an admission-cursor monitor: the serve thread holds one while it takes this core's
    // monitor, so a callback that needs the cursor STASHES and the reader fans after onDatagram
    // returns — seatNameFanStash)

    /**
     * Seat-name records a CTRL delivery callback resolved but must NOT fan in place: the callback
     * runs under the delivering session's reliability monitor, and {@link CtrlFanout#fanSeatName}
     * takes admission-cursor monitors — the announcer's OWN included (the echo target) — which the
     * serve thread holds in {@code sendUpTo} while it takes that same reliability monitor through
     * {@link #sendCtrlToSession} → {@code ctrlSend}: core → cursor against cursor → core, a cycle
     * reachable on every normal join (the kit sends SNAM right after WELCOME, while its cursor
     * drains). READER THREAD ONLY: filled inside {@code onDatagram}, drained by
     * {@link #fanStashedSeatNames} right after it returns (no monitor held), cleared on a reader
     * fault.
     */
    private final ArrayList<SeatNameFrameCodec.SeatNameRecord> seatNameFanStash = new ArrayList<>(2);

    /** Reader thread, right after {@code onDatagram} returned: fan every record the delivery
     *  callback stashed (see {@link #seatNameFanStash}), outside every monitor. */
    private void fanStashedSeatNames() {
        if (seatNameFanStash.isEmpty()) {
            return;
        }
        for (int i = 0; i < seatNameFanStash.size(); i++) {
            fans.fanSeatName(seatNameFanStash.get(i));
        }
        seatNameFanStash.clear();
    }

    /** CTRL in-order delivery: route by the payload frame's magic (the table's UP column). */
    private void onCtrlDelivered(PeerSession session, long seq, byte[] payload) {
        int magic = payloadMagic(payload);
        synchronized (lock) { // AUTH-1 + parse-bounds-5 — a BYE'd / failed
            // session's CTRL (incl. what the in-order drain delivers after seq N killed it) runs no handler. CHTU
            // keeps its OWN counter (chatStaleSessionDropped, smoke BY2/BY3) but is dropped HERE too, undecoded
            // (delta scan server-wire-decode-1): exempt, a dead session's MALFORMED CHTU reached onChatUp's
            // decode ahead of its stale check and failed the dead session loud as ctrl_corrupt (row BY10).
            // PidsChatVerbs keeps its own check for a live session whose seat is vacated.
            if (!session.isLiveLocked()) {
                if (magic == ChatFrameCodec.UP_MAGIC) {
                    counters.chatStaleSessionDropped++;
                } else {
                    counters.ctrlStaleSessionDropped++;
                }
                return;
            }
        }
        try {
            if (session.observer && (magic == FireDischargeDeclarationCodec.MAGIC
                    || magic == MarkFrameCodec.MAGIC)) {
                // seat-scoped UP kinds from an observer are dropped at ingest —
                // a PDCL for ship 0 would PASS the pin (session.shipId is 0 too) and mint
                // a phantom apply-state at the drain; a MARK keys on the sender's seat. SNAM
                // (stored, never fanned for seat 0), SNNK repair, CHTU chat, PSTE stay. A VSUB
                // (W15 v0, authors a pick for a seat the observer does not hold) is ALSO never
                // honoured, but it is now routed to onVariantSubmit, which
                // counts the same observerCtrlDrops and ANSWERS it (never a silent drop).
                readerCounters.observerCtrlDrops++;
            } else if (magic == FireDischargeDeclarationCodec.MAGIC) {
                onDeclaration(session, payload);
            } else if (magic == SeatNameFrameCodec.ANNOUNCE_MAGIC) {
                SeatNameFrameCodec.SeatNameAnnounce announce = SeatNameFrameCodec.decodeAnnounce(payload); // Exact length
                SeatNameFrameCodec.SeatNameRecord stored = null;
                synchronized (lock) {
                    if (session.seatNameAnnounce == null) {
                        session.seatNameAnnounce = announce;
                        if (session.seatId >= 1) {
                            // Gather the announcer's resolved record
                            // under lock, fan OUTSIDE (a dup announce never re-fans —
                            // first-write-wins; a takeover minted a fresh session, so its
                            // re-announce stores and replaces on every client).
                            stored = resolvedSeatNameRecordLocked(session);
                        }
                    }
                    lock.notifyAll();
                }
                if (stored != null && stored.hasName()) {
                    // Sentinel-resolved records are SKIPPED, not fanned: a name is never un-set
                    // (the client's ""-no-op law), so a nameless record is pure wire noise — and
                    // silence keeps no-name doors byte-quiet (the smoke legs' seq arithmetic).
                    // STASHED, never fanned here: this callback runs
                    // under the session's reliability monitor and the fan takes the announcer's own
                    // admission-cursor monitor — the reader fans it after onDatagram returns.
                    seatNameFanStash.add(stored);
                }
            } else if (magic == MatchReentryFrameCodec.MAGIC) {
                // Welded-only lane (the composition rule): a duplex/datagram serve has no reentry
                // gate. Counted, never fatal — a confused-but-authenticated peer.
                readerCounters.mrerIgnored++;
            } else if (magic == AwsdNackFrameCodec.MAGIC) {
                metaFrames.onAwsdNack(session, payload);
            } else if (magic == MarkFrameCodec.MAGIC) {
                metaFrames.onOwnerMark(session, payload);
            } else if (magic == ChatFrameCodec.UP_MAGIC) {
                chatVerbs.onChatUp(session, payload);
            } else if (magic == PostureFrameCodec.ECHO_MAGIC) {
                metaFrames.onPostureEcho(session, payload);
            } else if (magic == VariantSubmitFrameCodec.MAGIC) {
                variantSubmit.onVariantSubmit(session, payload);
            } else {
                failSession(session, new PilotPeerDisconnectedException("ctrl_bad_magic",
                        "pilot datagram CTRL bad magic: 0x" + Integer.toHexString(magic)
                                + " (expected PDCL declaration, SNAM announce, MRER,"
                                + " SNNK awsd nack, MARK owner mark, CHTU chat, PSTE"
                                + " posture echo, or VSUB variant submit)"));
            }
        } catch (IOException corrupt) {
            // Post-admission, CRC-verified payload that still fails its codec = a broken peer:
            // fail the SESSION loud (the TCP fail-closed posture), endpoint survives.
            failSession(session, new PilotPeerDisconnectedException("ctrl_corrupt",
                    "pilot datagram CTRL payload corrupt (seq " + seq + "): " + corrupt, corrupt));
        }
    }

    private void onDeclaration(PeerSession session, byte[] payload) throws IOException {
        FireDischargeDeclaration decl = FireDischargeDeclarationCodec.decodeDatagram(payload); //
        int budget = session.livenessDeclarationBudget;
        boolean declPinned = false;
        synchronized (lock) {
            session.declarationsSinceLastIntent++;
            if (budget > 0 && session.declarationsSinceLastIntent > budget) {
                // The per-exchange PDCL budget's datagram analog: declarations since the last
                // intent DEPOSIT (a strict producer interleaves them per frame; only a flood peer
                // accumulates a gap this large). Same hostile-or-dead posture as UpLaneDemux.
                failSessionLocked(session, new PilotPeerDisconnectedException("pdcl_budget_exceeded",
                        "pilot up-lane declaration budget exceeded: "
                                + session.declarationsSinceLastIntent
                                + " PDCL frames without a PINT intent (budget " + budget
                                + ") — peer treated as hostile-or-dead (socket-liveness)"));
                return;
            }
            if (multiPeer && decl.shipId() != session.shipId) {
                // N-mode DECLARATION pinning — the PINT
                // pin's PDCL twin. The drain routes by decl.shipId() (applyStateFor is a
                // computeIfAbsent), so an unpinned PDCL let a patched client force-fire ANOTHER
                // seat's mounts at its own aim point and mint phantom apply-states for arbitrary
                // ids. Same posture as the intent pin: drop + count, never enqueued, still
                // budget-charged, NEVER sink-recorded.
                // A .ccwr UP record has no sender seat, so a recorded
                // foreign-shipId PDCL reads as that ship's own declaration in the per-ship reel tools.
                session.pinnedDeclarationDropCount++;
                counters.pinnedDeclarationDropsTotal++;
                declPinned = true;
            } else if (session.pendingDeclarations.size() >= MAX_PENDING_DECLARATIONS) {
                counters.declarationsDropped++;
            } else {
                session.pendingDeclarations.addLast(decl);
            }
        }
        if (!declPinned) {
            recordUpPayload(session, payload);
        }
    }

    /**
     * W15 v0 submission-lane seam: the reader thread
     * hands a DECODED {@code VSUB} here after the transport gates (observer drop, seat bound,
     * rate bucket). The lane runs the pure {@code VariantPolicy} on the calling thread and
     * either returns a receipt to unicast NOW (a policy refusal) or deposits the submission
     * for the sim thread (mint, engine pass, register, VDEF fan) and returns {@code null} —
     * the sim thread then mails its verdict through {@link #sendVariantReceipt}. No lane
     * installed (the legacy/smoke door, or v0 not yet wired) = every submission is answered
     * {@code custom_unavailable}; nothing is minted anywhere.
     */
    public interface VariantSubmissionLane {
        VariantReceiptFrameCodec.VariantReceipt onSubmit(long sessionId, int seatId,
                String authorKey, VariantSubmitFrameCodec.VariantSubmit submit);

        /** The door mailed a reader-thread REJECT receipt returned by
         * {@link #onSubmit} — told AFTER the VACK left (the accept law's seed-after-VACK twin) so
         * the lane's reject listener can release a held pick without racing the toast. */
        default void onRejectMailed(String authorKey, String localId, String reasonToken) {
        }

        /** W15 v3 VCHK: the pure policy the door's PRE-ADMISSION dry-run runs on
         * the reader thread ({@link PidsVariantCheck#handleVariantCheckLocked}); {@code null} = no verdict
         * possible ({@code custom_unavailable}). Nothing else of the lane is reachable from
         * the dry-run — no offer, mint, deposit or cap slot. */
        default VariantPolicy policyOrNull() {
            return null;
        }

        /** Live {@code custom-submissions on/off}; a dry-run against an OFF lane is answered
         * {@code custom_disabled} (the deposit path's own token — the same word the player
         * would meet at submit). */
        default boolean accepting() {
            return true;
        }

        /** Peer-build sharing v2 ({@code variant-maker-peer-builds.md}): the ledger the door's
         * share verbs and the fielding auto-share read/flip (live-owner law inside). {@code null}
         * = no sharing on this door (the smoke's dry-run lane; every share verb answers
         * "unavailable", {@code /builds} lists nothing). */
        default SubmittedVariantLedger ledgerOrNull() {
            return null;
        }
    }

    volatile VariantSubmissionLane variantSubmissionLane;

    /** Install (or clear, {@code null}) the W15 submission lane; thread-agnostic. */
    public void setVariantSubmissionLane(VariantSubmissionLane lane) {
        variantSubmissionLane = lane;
    }

    /** W15 v0 step 3(b): the RUNTIME VDEF catalog — every accepted custom's
     * encoded frame, mint order — spooled to each newcomer AFTER the boot catalog so a late
     * joiner folds the customs live seats already hold (fold ≺ any RAPP row
     * naming the id). Null = none (the lane OFF). Read on the reader thread at admit. */
    private volatile java.util.function.Supplier<List<byte[]>> runtimeVariantFramesOrNull;

    /** Install (or clear, {@code null}) the runtime VDEF catalog supplier; thread-agnostic. */
    public void setRuntimeVariantCatalog(java.util.function.Supplier<List<byte[]>> framesOrNull) {
        runtimeVariantFramesOrNull = framesOrNull;
    }

    /** The server-generated WAVE VDEFs ({@code WaveLoadoutInflater.frames}), spooled to
     * each newcomer AFTER the boot + runtime catalogs (fold ≺ any RAPP row naming a {@code cc_wave_*} id;
     * a wave row can only name an id the inflater already spooled). Null = lane off. */
    private volatile java.util.function.Supplier<List<byte[]>> waveVariantFramesOrNull;

    /** Install (or clear, {@code null}) the wave VDEF spool supplier; thread-agnostic. */
    public void setWaveVariantCatalog(java.util.function.Supplier<List<byte[]>> framesOrNull) {
        waveVariantFramesOrNull = framesOrNull;
    }

    /**
     * W15 v0: mail ONE {@code VACK} to the submitting session — the {@link #sendAdminReceipt}
     * shape (resolve under {@code lock}, live-check, send OUTSIDE; thread-agnostic, so the
     * reader thread's immediate refusals and the sim thread's post-registration verdicts share
     * it). Prints the verdict row (a stdout row) and draws the hostile budget on a
     * hostile-class refusal — the {@link CustomVariantLane#VSUB_HOSTILE_BUDGET}th fails the
     * session loud with a machine token. A session gone between deposit and drain drops the
     * receipt with a row.
     */
    public void sendVariantReceipt(long sessionId, VariantReceiptFrameCodec.VariantReceipt receipt) {
        java.util.Objects.requireNonNull(receipt, "receipt");
        PeerSession target;
        int targetSeat;
        boolean kill = false;
        synchronized (lock) {
            target = sessionsById.get(sessionId);
            if (target == null || !target.isLiveLocked()) {
                System.out.println("pilot-datagram-server custom-variant receipt_dropped session="
                        + sessionId + " seq=" + receipt.submitSeq()
                        + " reason=" + (target == null ? "gone" : "not_live"));
                return;
            }
            targetSeat = target.seatId;
            if (!receipt.accepted()
                    && CustomVariantLane.isHostileCustomRefusal(receipt.reasonToken())) {
                kill = target.variantSubmit.drawHostile();
            }
            counters.variantReceiptsSent++;
        }
        System.out.println("pilot-datagram-server custom-variant verdict seat=" + targetSeat
                + " session=" + sessionId
                + " seq=" + receipt.submitSeq()
                + " verdict=" + (receipt.accepted() ? "ACCEPT" : "REJECT")
                + " id=" + PeerText.quote(receipt.variantId())
                + " reason=" + (receipt.accepted() ? "-" : receipt.reasonToken()));
        byte[] frame;
        try {
            frame = VariantReceiptFrameCodec.encode(receipt);
        } catch (IOException | RuntimeException unencodable) {
            // Mirror pilot-datagram-admission VCHK_ENCODE_FAILED in {@link PidsVariantCheck#answerLocked}. An
            // unencodable LAWFUL receipt (reason token + id were codec-bounded on the way in) is a
            // server defect, never a wire event; this runs on the READER thread (immediate
            // refusals) as well as the sim thread, so a throw here KILLS the reader. Counted +
            // rowed, nothing sent, the thread lives.
            // Atomic: this is the ONE reader-counter bump shared by two threads.
            readerCounters.variantReceiptsDropped.incrementAndGet();
            System.out.println("pilot-datagram-server custom-variant VACK_ENCODE_FAILED seat="
                    + targetSeat + " session=" + sessionId + " seq=" + receipt.submitSeq()
                    + " id=" + PeerText.quote(receipt.variantId()) + " " + unencodable);
            frame = null; // the hostile-budget kill below still runs (nothing is sent)
        }
        if (frame != null) {
            sendCtrlToSession(target, frame);
        }
        if (kill) {
            failSession(target, new PilotPeerDisconnectedException("custom_hostile_budget",
                    "pilot datagram session drew " + CustomVariantLane.VSUB_HOSTILE_BUDGET
                            + " hostile custom-variant refusals"));
        }
    }

    /** W3 L1b: the serve loop's tick heartbeat for dispatch-time transfer-floor math —
     * advisory begins read it at most one frame stale (fine for seconds-scale floors).
     * L1b-ii wires the per-tick call beside the serve's existing door touches. */
    public void noteSimTick(long simTick) {
        approxSimTick = simTick;
    }

    /**
     * The serve loop's once-per-tick LSTD drip — pop at
     * most {@link #LISTING_FRAMES_PER_TICK} frames per live session under the lock, send
     * outside it ({@link #sendCtrlToSession} is thread-agnostic; each frame is a real down
     * send recording its own {@code KIND_DOWN}). Bounded by construction: the queue holds at
     * most one listing (≤{@code MAX_SEQ_COUNT}=255 frames — a fresh mint replaces it), and
     * this metering delivers a max-cap listing in ≤32 ticks. If the reader
     * replaces the queue mid-drip, the client's latest-wins assembly drops the superseded
     * partial — lawful by design, never a partial render.
     */
    public void dripListingFrames() {
        for (PeerSession session : liveSessions()) {
            byte[][] toSend = null;
            synchronized (lock) {
                int n = Math.min(LISTING_FRAMES_PER_TICK, session.listingDrip.size());
                if (n > 0) {
                    toSend = new byte[n][];
                    for (int i = 0; i < n; i++) {
                        toSend[i] = session.listingDrip.pollFirst();
                    }
                    counters.listingFramesDripped += n;
                }
            }
            if (toSend != null) {
                for (byte[] frame : toSend) {
                    sendCtrlToSession(session, frame);
                }
            }
        }
    }

    /** W7 B1b: the seat's {@code /ship}-picked (or admit-seeded) variant (canonical allowlist id),
     * or null = the descriptor row. Serve-loop read at every seat fly-in AND at the round wrap
     * (the pick survives the wrap). */
    public String seatVariantOverrideOrNull(int seatId) {
        return chatCommands.overrideOrNull(seatId);
    }

    /** The RAW variant id the seat's FRESH claim requested ("" = none), or null
     * when no fresh admit is pending a first fly-in — consumed ONCE (serve-loop read at the
     * seat's first fly-in after admit; see {@link SeatVariantPicks#takeRequestedOrNull}). */
    public String takeRequestedVariantPickOrNull(int seatId) {
        return variantPicks.takeRequestedOrNull(seatId);
    }

    /** The server-owned joiner default (canonical pickable id) or null = descriptor
     * row. Reader-thread + serve-loop read. */
    public String joinerDefaultVariantOrNull() {
        return variantPicks.joinerDefaultVariantOrNull();
    }

    /**
     * Set (or clear with null/blank) the server-owned joiner default. The raw id is
     * canonicalized against the door's pickable allowlist — an unpickable id (or a door with
     * no pickables) is REFUSED loud and the standing value stays: the default can never name a
     * hull the server itself could not field. Returns the canonical id now standing, or null.
     */
    public String setJoinerDefaultVariant(String rawOrNull) {
        return variantPicks.setJoinerDefaultVariant(rawOrNull); // the mod scan alone
    }

    /**
     * The round-wrap law — every seat's {@code /ship} /
     * admit-seeded override SURVIVES the wrap (the W7 B1b v1 "reset returns every seat to its
     * descriptor" law is replaced by this one): the serve loop snapshots
     * {@link #seatVariantOverrideOrNull} per deployable seat and hands the map to the gated reset,
     * and a round-2 respawn reads the same store. Only the pick-request lane resets here — plus
     * the entries of seats NOT in {@code liveSeatIds} are dropped:
     * a vacated seat's pick dies with its tenure, never inherited by the next occupant.
     * {@code liveSeatIds} = the OCCUPIED seats (the serve
     * loop's redeploy set stays deployable): a fresh claim still behind its tenure gate, or a
     * seat admitted but not yet combat-ready, keeps its own pick across the wrap.
     */
    public void onRoundWrap(java.util.Set<Integer> liveSeatIds) {
        int dropped = chatCommands.retainOverrides(liveSeatIds);
        if (dropped > 0) {
            System.out.println("chat-command wrap_overrides_dropped vacated=" + dropped
                    + " kept=" + liveSeatIds.size() + " (a pick dies with its tenure)");
        }
        // A seat admitted but never flown in before the wrap
        // must not carry its raw request into the reset fly-in (a stray announce); W15: held
        // custom picks go with it.
        variantPicks.onRoundWrap();
    }

    /**
     * The lane's {@link CustomVariantLane.BanListener}: an operator
     * {@code custom-ban} / {@code custom-reject} newly banned these canonical ids — clear every
     * seat's seeded next-respawn override naming one (the dispatch's own monitor, released before
     * any door work), log + FALLBACK-announce each cleared seat, and chat the seat's LIVE session
     * (id-only, server-authored; a seat whose session already left clears silently). Sim thread
     * (the operator drain); the dispatch monitor is never nested under the door lock. Returns the
     * cleared-seat count for the receipt.
     */
    public int clearSeatVariantOverridesNaming(List<String> canonicalIds) {
        return clearSeatVariantOverridesNaming(canonicalIds, "banned", "was banned by the operator");
    }

    /** Boot VDEF catalog frames, 0 when none (the wiring's cap input). */
    public int bootVariantCatalogSize() {
        VariantCatalogStore catalog = variantCatalog;
        return catalog == null ? 0 : catalog.size();
    }

    /**
     * The per-seat clear line held to the chat law (the demote phrase
     * is 97 + id chars, so a base stem of 24+ chars overran {@link ChatFrameCodec#TEXT_MAX_CHARS} and
     * the encode THREW mid-loop): the name is cut to the room left, a trailing {@code ~} marking the cut.
     * The quotes carry the ship's DISPLAY NAME ({@link #overrideClearedName}),
     * never its id - a seat reads the ship it picked; the name is bounded again here
     * ({@link CatalogPromotionLane#chatShipName}), so any caller's text stays printable. Public for the contract row.
     */
    public static String overrideClearedLine(String name, String chatPhrase) {
        String head = "custom '";
        String tail = "' " + chatPhrase + " - your next respawn reverts to the server default";
        String bounded = CatalogPromotionLane.chatShipName(name, null);
        int room = ChatFrameCodec.TEXT_MAX_CHARS - head.length() - tail.length();
        String shown = bounded.length() <= room ? bounded : bounded.substring(0, Math.max(1, room - 1)) + "~";
        return head + shown + tail;
    }

    /** The name a pick-clear line shows for {@code canonical} - the allowlist's
     *  remembered display name (a shipped or kept ship), else the id itself (a player's own custom carries no
     *  remembered name), bounded. The audit rows beside the line keep the id. */
    String overrideClearedName(String canonical) {
        PickableVariants pickable = pickableVariants;
        return CatalogPromotionLane.chatShipName(pickable == null ? null : pickable.displayNameOrNull(canonical), canonical);
    }

    /**
     * A supersede / its put-back MOVES the standing picks naming {@code from} to
     * {@code to} ({@link SeatVariantPicks#moveStanding} over the dispatch's store, the take and every seed under the
     * dispatch's ONE monitor so no {@code /ship} lands between them); one audit row per seat, ids only, NO chat line
     * (the seat asked for that ship and still gets it, in its new build). A seed the store refuses (a hidden id) is
     * counted out and rowed. Sim thread (the operator drain / the catalog settle); returns the seats moved.
     */
    public int moveSeatVariantOverrides(String from, String to) {
        synchronized (chatCommands) {
            return SeatVariantPicks.moveStanding(new SeatVariantPicks.StandingStore() {
                @Override
                public java.util.Map<Integer, String> takeNaming(java.util.Collection<String> canonicalIds) {
                    return chatCommands.clearOverridesNaming(canonicalIds);
                }

                @Override
                public boolean put(int seatId, String canonical) {
                    boolean seeded = chatCommands.seedOverride(seatId, canonical, null, pickableVariants).seeded();
                    System.out.println("pilot-datagram-admission VARIANT_OVERRIDE_MOVED seat=" + seatId
                            + " from=" + PeerText.quote(from) + " to=" + PeerText.quote(canonical)
                            + (seeded ? "" : " refused=hidden (the seat falls back to the server default)"));
                    return seeded;
                }
            }, from, to);
        }
    }

    /** The same clear for a catalog DEMOTE ({@code why} = the log + announce token). */
    public int clearSeatVariantOverridesNaming(List<String> canonicalIds, String why, String chatPhrase) {
        java.util.Map<Integer, String> cleared = chatCommands.clearOverridesNaming(canonicalIds);
        if (cleared.isEmpty()) {
            return 0;
        }
        List<PeerSession> live = liveSessions();
        for (java.util.Map.Entry<Integer, String> e : cleared.entrySet()) {
            int seatId = e.getKey();
            String canonical = e.getValue();
            PeerSession target = null;
            for (PeerSession s : live) {
                if (s.seatId() == seatId) {
                    target = s;
                }
            }
            System.out.println("pilot-datagram-admission VARIANT_OVERRIDE_CLEARED seat=" + seatId
                    + " custom=" + PeerText.quote(canonical) + " reason=" + why
                    + " session=" + (target == null ? "none" : Long.toString(target.sessionId()))
                    + " (next respawn = server default)");
            // Shape: a pick the server will no longer honor is a FALLBACK every
            // debrief must list (the reject twin: onCustomRejected).
            FallbackAnnounce.announceOnce("custom-" + why + "-override-seat-" + seatId,
                    "custom=" + PeerText.quote(canonical) + " reason=" + why + " (next respawn = server default)");
            if (target != null) {
                try {
                    chatToSession(target.sessionId(), seatId, overrideClearedLine(overrideClearedName(canonical), chatPhrase));
                } catch (RuntimeException chatFault) {
                    // one seat's line never skips the later seats' CLEARED / FALLBACK rows
                    System.out.println("pilot-datagram-admission VARIANT_OVERRIDE_CLEARED chat_fault seat=" + seatId
                            + " " + chatFault.getClass().getSimpleName());
                }
            }
        }
        return cleared.size();
    }

    /** W7 B1b: the serve loop records each seat fly-in's DEPLOYED variant here (the listing's
     * {@code (piloting)} mark; overwrites — last deploy wins). */
    public void recordSeatFieldedVariant(int seatId, String variantId) {
        chatCommands.recordFielded(seatId, variantId);
        autoShareFielded(seatId, variantId);
    }

    /**
     * Peer-build sharing v2 — the fielding seam: the seat's live session with the
     * auto-share flag ON shares the DEPLOYED id silently when it is an own accepted custom the
     * author never {@code /unshare}d (the ledger decides; stock ids skip before any lock). Serve
     * thread; the door lock for the seat→session read ONLY, the ledger monitor after it — the two
     * are never nested (the reclaim's three-step law below).
     */
    private void autoShareFielded(int seatId, String variantId) {
        if (variantId == null || !variantId.startsWith(SubmittedVariantLedger.ID_PREFIX)) {
            return;
        }
        VariantSubmissionLane lane = variantSubmissionLane;
        SubmittedVariantLedger ledger = lane == null ? null : lane.ledgerOrNull();
        if (ledger == null) {
            return;
        }
        PeerSession owner = null;
        synchronized (lock) {
            for (PeerSession s : sessionsById.values()) {
                if (s.seatId == seatId && !s.observer && s.isLiveLocked()) {
                    owner = s;
                    break;
                }
            }
            if (owner == null || !owner.autoShareBuilds) {
                return;
            }
        }
        reclaimDepartedOwnerBuilds(ledger, owner);
        SubmittedVariantLedger.ShareResult r = ledger.autoShareFielded(variantId, owner.sessionId);
        if (r.outcome() == SubmittedVariantLedger.ShareOutcome.SHARED) {
            System.out.println("pilot-datagram-server peer_build auto_shared seat=" + seatId
                    + " session=" + owner.sessionId + " id=" + PeerText.quote(variantId)
                    + " shared=" + ledger.sharedCount());
        }
    }

    /**
     * Ownership amendment: the SAME author key re-joining (a new session; the kit
     * re-offers its pick at every connect and is dup-answered the OLD canonical) reclaims every
     * accepted id whose recorded owner session is GONE. Three separate steps so the door lock and
     * the ledger monitor never nest: (1) ledger — the candidates + their recorded owner ids;
     * (2) door lock — which of those owners is no longer live; (3) ledger — re-bind each (the
     * ledger re-checks the author key). A live author's rows are never touched: a HELLO-string
     * spoof of a PRESENT player reclaims nothing.
     */
    private int reclaimDepartedOwnerBuilds(SubmittedVariantLedger ledger, PeerSession session) {
        List<SubmittedVariantLedger.OwnerRow> candidates =
                ledger.reclaimCandidates(customAuthorKey(session), session.sessionId);
        if (candidates.isEmpty()) {
            return 0;
        }
        List<String> departed = new ArrayList<String>(candidates.size());
        synchronized (lock) {
            for (SubmittedVariantLedger.OwnerRow c : candidates) {
                PeerSession recorded = sessionsById.get(c.ownerSessionId());
                if (recorded == null || !recorded.isLiveLocked()) {
                    departed.add(c.canonicalId());
                }
            }
        }
        int reclaimed = 0;
        for (String id : departed) {
            if (ledger.reclaimOwner(id, session.sessionId, customAuthorKey(session))) {
                reclaimed++;
            }
        }
        if (reclaimed > 0) {
            System.out.println("pilot-datagram-server peer_build reclaim seat=" + session.seatId
                    + " session=" + session.sessionId + " ids=" + reclaimed);
        }
        return reclaimed;
    }

    /**
     * Peer-build sharing v2: the share verbs, door-answered on the reader thread. Gate =
     * the same customs gate as {@code /customs} plus a share-capable lane; every reply is a
     * constant or a constant + a SERVER-minted canonical id (the refusal echoes nothing). The
     * broadcast line fans on the first OFF→ON of an id per match, non-silent only.
     */
    List<String> handlePeerBuildShare(PeerSession session, int seatId,
            ChatCommands.Parsed parsed, ChatCommandDispatch.ListingSink listingSink) {
        VariantSubmissionLane lane = variantSubmissionLane;
        SubmittedVariantLedger ledger = lane == null ? null : lane.ledgerOrNull();
        if (ledger == null || !variantPicks.customsAvailable()) {
            return List.of("custom variants unavailable on this server");
        }
        long sessionId = session.sessionId;
        ChatCommands.Kind kind = parsed.kind();
        if (kind == ChatCommands.Kind.SHARE_AUTO || kind == ChatCommands.Kind.SHARE_AUTO_SILENT) {
            boolean on = "on".equals(parsed.arg());
            synchronized (lock) {
                session.autoShareBuilds = on;
            }
            System.out.println("pilot-datagram-server peer_build auto_share seat=" + seatId
                    + " session=" + sessionId + " on=" + on
                    + " silent=" + (kind == ChatCommands.Kind.SHARE_AUTO_SILENT));
            if (kind == ChatCommands.Kind.SHARE_AUTO_SILENT) {
                return List.of();
            }
            return List.of(on ? "auto-share on: builds you field are listed under /builds"
                    : "auto-share off");
        }
        if (kind == ChatCommands.Kind.BUILDS_LIST) {
            List<String> ids = ledger.sharedIds();
            System.out.println("pilot-datagram-server peer_build builds_list seat=" + seatId
                    + " session=" + sessionId + " shared=" + ids.size());
            return ChatCommandDispatch.emitBuilds(ids, listingSink);
        }
        reclaimDepartedOwnerBuilds(ledger, session);
        if (kind == ChatCommands.Kind.SHARE_LIST) {
            List<SubmittedVariantLedger.ShareRow> rows = ledger.ownShareRows(sessionId);
            System.out.println("pilot-datagram-server peer_build share_list seat=" + seatId
                    + " session=" + sessionId + " own=" + rows.size());
            return ChatCommandDispatch.emitShareRows(rows, listingSink);
        }
        String id = parsed.arg();
        if (kind == ChatCommands.Kind.UNSHARE_SET) {
            SubmittedVariantLedger.ShareResult r = ledger.unshare(id, sessionId);
            System.out.println("pilot-datagram-server peer_build unshare seat=" + seatId
                    + " session=" + sessionId + " id=" + PeerText.quote(id)
                    + " outcome=" + r.outcome());
            // the echo below is the LEDGER's canonical id (server-minted), never the wire arg
            return switch (r.outcome()) {
                case UNSHARED -> List.of("no longer shared: " + r.entry().canonicalId());
                case ALREADY_UNSHARED -> List.of("not shared: " + r.entry().canonicalId());
                default -> List.of(ChatCommands.SHARE_REFUSED);
            };
        }
        // An owned custom whose loadout IS a /ship-pickable build -> no share, no broadcast
        String stockReply = StockBuildRedirect.replyOrNull(ledger.ownedCanonicalOrNull(id, sessionId),
                variantCatalog, pickableVariants);
        if (stockReply != null) {
            System.out.println("pilot-datagram-server peer_build share_stock_redirect seat=" + seatId
                    + " session=" + sessionId + " id=" + PeerText.quote(id));
            return List.of(stockReply);
        }
        boolean silent = kind == ChatCommands.Kind.SHARE_SET_SILENT;
        SubmittedVariantLedger.ShareResult r = ledger.share(id, sessionId, silent);
        System.out.println("pilot-datagram-server peer_build share seat=" + seatId
                + " session=" + sessionId + " id=" + PeerText.quote(id)
                + " outcome=" + r.outcome() + " announce=" + r.announce()
                + " shared=" + ledger.sharedCount());
        switch (r.outcome()) {
            case SHARED -> {
                if (r.announce()) {
                    fanServerChat(seatId, ChatCommands.shareBroadcastLine(seatId,
                            r.entry().canonical().displayName(), r.entry().canonical().hullId()));
                }
                return List.of("shared: " + r.entry().canonicalId() + " (peers see it under /builds)");
            }
            case ALREADY_SHARED -> {
                return List.of("already shared: " + r.entry().canonicalId());
            }
            default -> {
                return List.of(ChatCommands.SHARE_REFUSED);
            }
        }
    }

    /** Ops-GUI ship column (it once read "?" for a seat whose
     * side carries several SEAT rows): the seat's last DEPLOYED variant, null before its first
     * fly-in. Thread-agnostic (the dispatch's own monitor); the ops writer's 1/s read. */
    public String seatFieldedVariantOrNull(int seatId) {
        return chatCommands.fieldedOrNull(seatId);
    }

    // The 10 public DOWN fans delegate to CtrlFanout (the
    // fanCtrlDown skeleton + all 12 per-kind fans live there; signatures unchanged, zero caller edits).
    /** {@link CtrlFanout#fanServerChat} — the DOWN-fan skeleton lives there. */
    public void fanServerChat(int seatId, String text) { fans.fanServerChat(seatId, text); }

    /** {@link CtrlFanout#fanHulkFracture} — the DOWN-fan skeleton lives there. */
    public void fanHulkFracture(HulkFractureFrameCodec.FractureEdge edge) { fans.fanHulkFracture(edge); }

    /** {@link CtrlFanout#fanMatchOver} — the DOWN-fan skeleton lives there. */
    public void fanMatchOver(MatchOverFrameCodec.MatchOverRecord record) { fans.fanMatchOver(record); }

    /** {@link CtrlFanout#fanHulkPose} — the DOWN-fan skeleton lives there. */
    public void fanHulkPose(HulkPoseFrameCodec.PoseKeyframe keyframe) { fans.fanHulkPose(keyframe); }

    /** {@link CtrlFanout#fanAsteroidPose} — the DOWN-fan skeleton lives there. */
    public void fanAsteroidPose(byte[] frame) { fans.fanAsteroidPose(frame); } //

    /** {@link CtrlFanout#fanHulkCutGeometry} — the DOWN-fan skeleton lives there. */
    public void fanHulkCutGeometry(HulkCutGeometryFrameCodec.CutGeometryRecord record) { fans.fanHulkCutGeometry(record); }

    /** {@link CtrlFanout#fanRosterAppend} — the DOWN-fan skeleton lives there. */
    public void fanRosterAppend(byte[] frame, int rowCount) { fans.fanRosterAppend(frame, rowCount); }

    /** {@link CtrlFanout#fanModuleMount} — the DOWN-fan skeleton lives there. */
    public void fanModuleMount(byte[] frame, int kind) { fans.fanModuleMount(frame, kind); }

    /** {@link CtrlFanout#fanTransfer} — the DOWN-fan skeleton lives there. */
    public void fanTransfer(byte[] frame, int kind) { fans.fanTransfer(frame, kind); }

    /** {@link CtrlFanout#fanPilotAim} — the DOWN-fan skeleton lives there. */
    public void fanPilotAim(byte[] frame) { fans.fanPilotAim(frame); }

    /**
     * One session's resolved {@code SNMR} record (lock held; callers skip unbound
     * seats). The legacy SNMS fallback semantics, per seat: wire announce → the server-JVM
     * {@code cc.match.seat1Name}/{@code seat2Name} knobs for seats 1/2 → the {@code ""}
     * no-override sentinel (which the fan/catch-up then SKIP — a name is never un-set, so a
     * sentinel record says nothing a client could apply).
     */
    private SeatNameFrameCodec.SeatNameRecord resolvedSeatNameRecordLocked(PeerSession session) {
        SeatNameFrameCodec.SeatNameAnnounce announce = session.seatNameAnnounce;
        String name = announce != null && announce.hasName() ? announce.name() : "";
        if (name.isEmpty() && (session.seatId == 1 || session.seatId == 2)) {
            // Full literals (not a "cc.match.seat"+seatId concat) so check-knobs can see the reads.
            String knob = System.getProperty(
                    session.seatId == 1 ? "cc.match.seat1Name" : "cc.match.seat2Name");
            String trimmed = knob == null ? "" : knob.trim();
            if (!trimmed.isEmpty()) {
                try {
                    SeatNameFrameCodec.validateSeatName(trimmed);
                    name = trimmed;
                } catch (IllegalArgumentException bad) {
                    // A malformed config knob must not kill the fan: sentinel + one readable row.
                    System.out.println("pilot-datagram-server seat_name_knob_invalid seat="
                            + session.seatId + " reason=" + bad.getMessage());
                }
            }
        }
        return new SeatNameFrameCodec.SeatNameRecord(session.seatId, session.shipId, name);
    }

    /** ONE law for seat &lt; 1 — the session's
     *  resolved SNMR NAME for callers that want only the name (lock held): {@code ""} for an
     *  observer (seat 0 has no seat name, and {@link SeatNameFrameCodec.SeatNameRecord} refuses
     *  seat &lt; 1 — the serve death), else {@link #resolvedSeatNameRecordLocked}'s name
     *  ({@code ""} = nameless). */
    String resolvedSeatNameLocked(PeerSession session) {
        return session.seatId < 1 ? "" : resolvedSeatNameRecordLocked(session).name();
    }

    /**
     * late-joiner catch-up: single-target reliable CTRL send — the
     * {@link #spoolSeatNameCatchUp} idiom (spool outside lock; EACH frame is a real down send
     * recording its own {@code KIND_DOWN}). Thread-agnostic like the fan skeleton: the
     * SERVE-LOOP thread calls it at the newly-live-session seam (replaying the round's RAPP
     * append-log to a joiner admitted after a wave_spawn, who otherwise repeats the ghost
     * match verbatim); the READER thread calls it for private chat-command replies (W7 B1a).
     */
    public void sendCtrlToSession(PeerSession session, byte[] frame) {
        ctrlSpoolOrFail(session, frame); // outside lock (lock-order rule)
        WireRecordSink s = sink;
        if (s != null) {
            s.record(WireRecordSink.KIND_DOWN, frame);
        }
    }

    /**
     * One SEAT baseline row to one session — the paced admission / round-wrap re-baseline's send
     * primitive, the CTRL half of {@link #sendTickToSession} for a single frame: spool only, NO
     * per-frame {@code KIND_DOWN} record (the serve tick's one explicit-stamp record is the sink
     * truth for seat rows, {@link #recordTickDown}), and no stamp (the caller stamped the session
     * at admit or wrap). Outside {@code lock}; a dead session is a silent no-op. Serve thread,
     * under the session's admission-cursor monitor.
     */
    public void sendSeatBaselineToSession(PeerSession session, byte[] frame) {
        requireMultiPeerDoor("sendSeatBaselineToSession");
        ctrlSpoolOrFail(session, frame);
    }

    /**
     * The admin {@code /wave} receipt return-leg — the SERVE-LOOP thread mails
     * the drained verb's REAL effect back to the depositing session as its chat reply (the
     * door sent no inline reply; texts = {@link AdminWaveQueue}'s builders). Resolve under
     * {@code lock}, send outside (lock-order rule; {@link #sendCtrlToSession} is
     * thread-agnostic). A session gone or dying between deposit and drain drops the receipt
     * with a log row — the effect row in the serve log stays the durable truth.
     */
    public void sendAdminReceipt(long sessionId, String line) {
        // Every receipt is held to the chat law HERE, the one seam every
        // receipt caller reaches — a receipt that echoes a long id can pass 120 chars, and an over-long or
        // untrimmed line would throw in the encoder and turn a real verdict into the operator-fault reply.
        String text = clipReceipt(line);
        if (text == null || text.isEmpty()) {
            System.out.println("pilot-datagram-server admin_receipt_dropped session=" + sessionId + " reason=blank");
            return;
        }
        PeerSession target;
        int targetSeat;
        synchronized (lock) {
            target = sessionsById.get(sessionId);
            if (target == null || !target.isLiveLocked()) {
                System.out.println("pilot-datagram-server admin_receipt_dropped session="
                        + sessionId + " reason=" + (target == null ? "gone" : "not_live"));
                return;
            }
            targetSeat = target.seatId;
        }
        sendCtrlToSession(target, ChatFrameCodec.encodeDown(targetSeat, "server", text));
    }

    /**
     * The admin receipt's chat-law clip: trimmed, cut to {@link ChatFrameCodec#TEXT_MAX_CHARS} (never splitting a
     * surrogate pair), then trimmed again so the cut never leaves a trailing space. Null stays null, blank becomes
     * empty (the caller drops both). Pure; public for the contract row.
     *
     */
    public static String clipReceipt(String line) {
        if (line == null) {
            return null;
        }
        String text = line.trim();
        if (text.length() > ChatFrameCodec.TEXT_MAX_CHARS) {
            int cut = ChatFrameCodec.TEXT_MAX_CHARS;
            if (Character.isHighSurrogate(text.charAt(cut - 1))) {
                cut--;
            }
            text = text.substring(0, cut).trim();
        }
        return text;
    }

    /**
     * Admission catch-up: spool the current NAMED table (every live
     * bound seat that resolves to a real name, the newcomer's own record included — seats 1/2
     * may resolve off the config knobs with no announce at all) to the NEWCOMER only.
     * Sentinel-resolved records are skipped like the fan skips them (nothing to say; a no-name
     * door stays byte-quiet). Gather under lock, spool outside; each frame is a real down send,
     * so each records {@code KIND_DOWN}. Arrival-vs-catch-up duplicates are possible — client
     * apply is idempotent. Flood-bounded by construction: once per admission. On a paced serve
     * the encoded table is bound to the joiner's admission cursor instead of spooled here.
     */
    private void spoolSeatNameCatchUp(PeerSession newcomer) {
        List<SeatNameFrameCodec.SeatNameRecord> table =
                new ArrayList<SeatNameFrameCodec.SeatNameRecord>();
        synchronized (lock) {
            for (PeerSession session : sessionsById.values()) {
                if (session.isLiveLocked() && session.seatId >= 1) {
                    SeatNameFrameCodec.SeatNameRecord record =
                            resolvedSeatNameRecordLocked(session);
                    if (record.hasName()) {
                        table.add(record);
                    }
                }
            }
        }
        if (table.isEmpty()) {
            return;
        }
        List<byte[]> frames = new ArrayList<byte[]>(table.size());
        LinkedHashMap<Integer, byte[]> rows = new LinkedHashMap<Integer, byte[]>();
        for (SeatNameFrameCodec.SeatNameRecord record : table) {
            // Defect C: catch-up resolves knob-fallback names no announce ever
            // fanned — teach the arbiter here too (outside lock; hasName()-guarded above).
            if (moduleSeats != null) {
                moduleSeats.noteSeatName(record.seatId(), record.name());
            }
            byte[] frame = SeatNameFrameCodec.encodeSeatNameRecord(
                    record.seatId(), record.shipId(), record.name());
            frames.add(frame);
            rows.put(record.seatId(), frame);
        }
        // A PACED serve hands the table to the joiner's
        // admission cursor, which sends it as the LAST segment (names after bodies), each frame
        // through sendCtrlToSession — so each still records its own KIND_DOWN, only later and on
        // the serve thread. ORDER, the cursor's caller contract: this bind runs BEFORE the voice
        // sheet and before handleHello's finally marks the session provisioned (an unprovisioned
        // cursor sends and completes nothing, so the names are always bound before it can
        // finish); no door lock is held here (the cursor monitor precedes the door lock).
        RosterAppendCatchup<PeerSession> cursor = admissionCursor;
        if (cursor != null && cursor.bindSeatNames(newcomer.sessionId(), rows)) {
            System.out.println("pilot-datagram-server seat_name_bound session="
                    + newcomer.sessionId() + " records=" + rows.size());
            return;
        }
        WireRecordSink s = sink;
        for (byte[] frame : frames) {
            ctrlSpoolOrFail(newcomer, frame); // outside lock (lock-order rule)
            if (s != null) {
                s.record(WireRecordSink.KIND_DOWN, frame);
            }
        }
        System.out.println("pilot-datagram-server seat_name_catchup session="
                + newcomer.sessionId() + " records=" + table.size());
    }

    /**
     * W7c B2: spool the pre-encoded VDEF catalog to the NEWCOMER only,
     * once per admission — the delivery lane for wire-only variant definitions, strictly a
     * PREREQUISITE for any RAPP row naming one. ARMED doors only ({@code requiredDoorFingerprint}
     * non-null): the door's exact-fingerprint pre-check already guarantees every admitted session
     * runs the CURRENT fold-capable kit, so no capability manifest (and no hs bump) is needed —
     * unarmed/smoke doors emit nothing, byte-identical to pre-B2. Re-delivery on takeover is
     * harmless: the client fold registers with overwrite=true (D3 idempotency). Each frame is a
     * real down send recording its own {@code KIND_DOWN} ({@link #sendCtrlToSession}).
     */
    private void spoolVariantCatalogCatchUp(PeerSession newcomer) {
        if (requiredDoorFingerprint == null) {
            return; // unarmed/smoke door: the kit is not proven fold-capable — deliver NOTHING
        }
        VariantCatalogStore catalog = variantCatalog;
        int bootFrames = 0;
        if (catalog != null && !catalog.isEmpty()) {
            for (byte[] frame : catalog.frames()) {
                sendCtrlToSession(newcomer, frame); // outside lock (lock-order rule)
            }
            bootFrames = catalog.size();
        }
        // W15 v0 step 3(b): the RUNTIME catalog (accepted customs, mint order) AFTER the boot
        // frames — the guard above is split so an empty boot catalog never drops the customs.
        java.util.function.Supplier<List<byte[]>> runtime = runtimeVariantFramesOrNull;
        int runtimeFrames = 0;
        if (runtime != null) {
            for (byte[] frame : runtime.get()) {
                sendCtrlToSession(newcomer, frame);
                runtimeFrames++;
            }
        }
        // The wave lane's generated bodies last (mint order) — same fold, same law.
        java.util.function.Supplier<List<byte[]>> wave = waveVariantFramesOrNull;
        int waveFrames = 0;
        if (wave != null) {
            for (byte[] frame : wave.get()) {
                sendCtrlToSession(newcomer, frame);
                waveFrames++;
            }
        }
        if (bootFrames == 0 && runtimeFrames == 0 && waveFrames == 0) {
            return;
        }
        System.out.println("pilot-datagram-server variant_catalog_catchup session="
                + newcomer.sessionId() + " frames=" + bootFrames + " runtime_frames=" + runtimeFrames
                + " wave_frames=" + waveFrames);
    }

    /**
     * Spool the ONE {@code PSTB} posture wire block to the NEWCOMER, once per
     * admission (the {@link #spoolVariantCatalogCatchUp} idiom — outside lock, a real down send
     * recording its own {@code KIND_DOWN} via {@link #sendCtrlToSession}). Schema 1 is
     * knob-empty by design; the frame's whole payload is the serve's own experiment tag
     * ({@link PostureRegistry#experimentTagOrNull}), stamping the admission so the client's
     * sidelog dump + the echo verdict key on the SERVER-SET posture. UNCONDITIONAL — unlike the
     * variant catalog there is no armed-door gate: the kit-compat gate is the M4-B hs bump (an
     * old kit bounces at HELLO instead of dying on the unknown magic), and unarmed internal
     * rigs are exactly where posture-drift detection matters. A tag the wire contract rejects
     * (>64 UTF-8 bytes — server-local config error, never wire input) falls back to a tagless
     * block LOUDLY rather than killing the admission path: the detection lane stays alive and
     * the operator sees the bad tag at the first admission.
     */
    private void spoolPostureBlock(PeerSession newcomer) {
        String tag = PostureRegistry.experimentTagOrNull();
        int[] knobs = WireKnobState.pairs(); // Schema 2 carries the live census
        byte[] frame;
        try {
            frame = PostureFrameCodec.encodeBlock(tag, knobs);
        } catch (IllegalArgumentException badTag) {
            System.out.println("pilot-datagram-server posture_block_tag_invalid session="
                    + newcomer.sessionId() + " reason=" + badTag.getMessage()
                    + " (sending tagless block; fix cc.posture.experiment)");
            tag = null;
            frame = PostureFrameCodec.encodeBlock(null, knobs);
        }
        sendCtrlToSession(newcomer, frame); // outside lock (lock-order rule)
        System.out.println("pilot-datagram-server posture_block_catchup session="
                + newcomer.sessionId() + " tag=" + (tag == null ? "none" : tag)
                + " knobs=" + WireKnobState.summary());
    }

    /**
     * re-spool the {@code PSTB} block to EVERY live session after an operator
     * {@code /knob} flip ({@link AdminKnobVerb}) — the client's latch is latest-wins and its drain
     * re-applies the whole {@link WireKnob} census, so a mid-match flip lands on each seat's next
     * combat poll. Snapshot under {@code lock}, send outside (lock-order rule). Returns the count.
     */
    public int respoolPostureBlockToAll() {
        List<PeerSession> live = new ArrayList<PeerSession>();
        synchronized (lock) {
            for (PeerSession s : sessionsById.values()) {
                if (s.isLiveLocked()) {
                    live.add(s);
                }
            }
        }
        for (PeerSession s : live) {
            spoolPostureBlock(s);
        }
        return live.size();
    }

    /** SNAP fresh delivery: the UP intent lane — decode PINT, deposit latest-wins.
     * The datagram door's intent pinning: a PINT whose shipId is not the session's
     * authorized wire id is dropped + counted (fail-closed cross-seat spoof) and NEVER
     * sink-recorded. A {@code .ccwr} UP
     * record carries no sender seat; the counters + {@code session_dead} {@code pinned_intent_drops=}
     * are the evidence. Own-ship stale/queue-full PDCLs still record. */
    private void onSnapDelivered(PeerSession session, long seq, byte[] payload) {
        synchronized (lock) { // AUTH-1 — a BYE'd / failed session's PINT
            if (!session.isLiveLocked()) { // deposits nothing and is not sink-recorded (counted, never decoded)
                counters.snapStaleSessionDropped++; // ABOVE the magic kill — a dead
                return; // session's garbage SNAP is a counted stale drop, never a session_fail on a vacated seat (row BY9)
            }
        }
        int magic = payloadMagic(payload);
        if (magic != PilotIntentWireCodec.MAGIC) {
            failSession(session, new PilotPeerDisconnectedException("snap_bad_magic",
                    "pilot datagram SNAP bad magic: 0x" + Integer.toHexString(magic)
                            + " (expected PINT intent)"));
            return;
        }
        PilotIntentFrame intent;
        try {
            intent = PilotIntentWireCodec.decodeDatagram(payload); // Exact length
        } catch (IOException corrupt) {
            failSession(session, new PilotPeerDisconnectedException("pint_corrupt",
                    "pilot datagram PINT payload corrupt (snap seq " + seq + "): " + corrupt,
                    corrupt));
            return;
        }
        if (session.observer) {
            // An observer's intent stream is its liveness carrier and
            // nothing else — liveness was stamped at arrival (markArrivalLocked), the codec law
            // above still kills an out-of-domain PINT, and the deposit never happens: the serve
            // loop's take stays null, so combat_ready never latches seat 0 and no hull flies
            // in. Not sink-recorded either: the observer's UP is not gameplay wire truth.
            readerCounters.observerIntentDrops++;
            return;
        }
        boolean intentPinned = false;
        synchronized (lock) {
            if (multiPeer && intent.shipId() != session.shipId) {
                session.pinnedIntentDropCount++;
                counters.pinnedIntentDropsTotal++;
                intentPinned = true;
            } else {
                session.depositedIntent = intent; // one-slot latest-wins: overwrite IS the conflation
                session.depositStampTick = session.latestStampTick;
                // Edge bits OR-accumulate across the take window — for LEVEL bits
                // conflation is the contract, but a sub-tick edge-bit pulse (system press) must
                // survive to the take even when a later same-window frame overwrites it.
                session.pendingEdgeHeldOr |= intent.heldControls() & EDGE_HELD_LATCH_MASK;
                session.declarationsSinceLastIntent = 0;
            }
            lock.notifyAll();
        }
        if (!intentPinned) {
            recordUpPayload(session, payload);
        }
    }

    /** UP sink record at ARRIVAL (wire truth under conflation — a deposit overwritten before the
     * gate's take still happened on the wire), stamped with the session's latest deposit tick. */
    private void recordUpPayload(PeerSession session, byte[] payload) {
        WireRecordSink s = sink;
        if (s == null) {
            return;
        }
        long stamp = session.latestStampTick;
        if (stamp == NO_STAMP) {
            s.record(WireRecordSink.KIND_UP, payload);
        } else {
            s.record(WireRecordSink.KIND_UP, payload, stamp);
        }
    }

    /** The reliability cores' outbound sink: envelope + send (called under the owning core's
     * monitor — a socket send is the documented allowed blocking). Send failures are counted, not
     * thrown: a core must never see its sink throw, and datagram death is silence-based anyway.
     * The session's identity fields are final, so this needs no lock. */
    private void emitFromCore(PeerSession session, DatagramReliability.Emission emission) {
        DatagramFrameCodec.WireChannel channel = switch (emission.channel()) {
            case SNAP -> DatagramFrameCodec.WireChannel.SNAP;
            case CTRL -> DatagramFrameCodec.WireChannel.CTRL;
            case ACK -> DatagramFrameCodec.WireChannel.ACK;
        };
        byte[] datagram = DatagramFrameCodec.encode(channel, roundId, session.sessionId,
                emission.seq(), emission.ctrlAck(), emission.ctrlAckBits(), emission.snapHiSeen(),
                emission.payload());
        sendToSession(session, datagram);
    }

    /** Header-only envelope frame (BYE; WELCOME uses its payload lane) — outside the core's seq
     * spaces, current receiver state zeroed (the peer never feeds BYE to its reliability core). */
    private void sendEnvelopeOnly(PeerSession session, DatagramFrameCodec.WireChannel channel) {
        sendToSession(session, DatagramFrameCodec.encode(channel, roundId, session.sessionId,
                0L, 0L, 0, 0L, null));
    }

    boolean sendRawDatagram(byte[] datagram, InetSocketAddress to) {
        if (replay) {
            // The replay belt — the ONE socket send in the door, so nothing
            // leaves a replay door; counted, reported as sent (no caller treats it as a failure), and the
            // bytes / failure meters stay untouched (they measure the wire).
            suppressedSends.incrementAndGet();
            return true;
        }
        try {
            socket.send(new DatagramPacket(datagram, datagram.length, to));
            bytesSentTotal.addAndGet(datagram.length);
            return true;
        } catch (IOException sendFailed) {
            // UDP send failures are not a death signal (silence is); racing close() is benign.
            sendFailures.incrementAndGet();
            return false;
        }
    }

    /** Session-attributed send: the endpoint total counts inside
     * {@link #sendRawDatagram}; the per-seat TX twin counts only what actually reached the
     * socket, so marginal-burden attribution never includes swallowed failures. */
    void sendToSession(PeerSession session, byte[] datagram) {
        if (sendRawDatagram(datagram, session.peer)) {
            session.bytesSent.addAndGet(datagram.length);
        }
    }

    /** Cumulative endpoint-lifetime {@link #sendRawDatagram} failures (B5 E1(c) egress meter;
     * the serve loop stamps it on the {@code npeer-intent-rounds} line). */
    public long sendFailures() {
        return sendFailures.get();
    }

    /** Cumulative endpoint-lifetime valid SNNK AWSD-NACK arrivals (the npeer
     * round line's {@code awsd_nack_rx=}; coalesced dups still count, they arrived). */
    public long awsdNacksReceived() { synchronized (lock) { return counters.awsdNacksReceived; } }

    /** SNNK arrivals dropped at a full per-session pending set (the flood bound). */
    public long awsdNacksDropped() { synchronized (lock) { return counters.awsdNacksDropped; } }

    /** Cumulative valid MARK F9 owner-mark rows printed (the npeer round
     * line's {@code mark_rx=}; inside-window arrivals are counted in the drop twin instead). */
    public long ownerMarksReceived() { synchronized (lock) { return counters.ownerMarksReceived; } }

    /** MARK arrivals dropped inside the per-seat {@link #MARK_MIN_INTERVAL_MILLIS} window. */
    public long ownerMarksRateDropped() { synchronized (lock) { return counters.ownerMarksRateDropped; } }

    /** Cumulative CHTU sends relayed as a CHTD fan (readable on both doors). */
    public long chatRelayed() { synchronized (lock) { return counters.chatRelayed; } }

    /** CHTU sends dropped at an empty per-session token bucket (the policy drop). */
    public long chatRateDropped() { synchronized (lock) { return counters.chatRateDropped; } }

    /** CHTU sends dropped for an unbound-seat sender (null-authority smoke door only —
     * CHTD requires a real seat id, so there is nothing truthful to stamp). */
    public long chatUnseatedDropped() { synchronized (lock) { return counters.chatUnseatedDropped; } }

    /** CHTU sends dropped from a BYE'd / failed / seat-vacated sender. */
    public long chatStaleSessionDropped() { synchronized (lock) { return counters.chatStaleSessionDropped; } }

    /** non-CHTU CTRL frames dropped from a BYE'd / failed session. */
    public long ctrlStaleSessionDropped() { synchronized (lock) { return counters.ctrlStaleSessionDropped; } }

    /** PINT frames dropped from a BYE'd / failed session. */
    public long snapStaleSessionDropped() { synchronized (lock) { return counters.snapStaleSessionDropped; } }

    /** PSTE echoes not printed inside the per-session window. */
    public long postureEchoRateDropped() { synchronized (lock) { return counters.postureEchoRateDropped; } }

    /** W15 v0 VSUB frames decoded and past the rate gate (handed to the lane or refused). */
    public long variantSubmitsReceived() { synchronized (lock) { return counters.variantSubmitsReceived; } }

    /** W15 v0 VSUB frames answered {@code custom_rate_limited} (bucket empty). */
    public long variantSubmitsRateDropped() { synchronized (lock) { return counters.variantSubmitsRateDropped; } }

    /** W15 v0 VSUB frames from an unbound-seat sender (counted, answered {@code custom_unavailable};
     * an OBSERVER's VSUB counts under {@link PidsReaderCounters#observerCtrlDrops} instead). */
    public long variantSubmitsUnseatedDropped() { synchronized (lock) { return counters.variantSubmitsUnseatedDropped; } }

    /** W15 v0 VACK unicasts sent (both verdicts). */
    public long variantReceiptsSent() { synchronized (lock) { return counters.variantReceiptsSent; } }

    public long chatCommandsHandled() { synchronized (lock) { return counters.chatCommandsHandled; } }

    /** SS382 L3: no-arg listing LSTD mints (one per emitted listing, latest-wins). */
    public long listingsEmitted() { synchronized (lock) { return counters.listingsEmitted; } }

    /** SS382 L3: LSTD frames handed to the CTRL spool by the per-tick drip. */
    public long listingFramesDripped() { synchronized (lock) { return counters.listingFramesDripped; } }

    /** Cumulative SNMR fans (one per stored-SNAM trigger; admission catch-up
     * spools are counted per admission in the {@code seat_name_catchup} row, not here). */
    public long seatNamesFanned() { synchronized (lock) { return counters.seatNamesFanned; } }

    /** Cumulative HKFR fans (one per drained fracture edge). */
    public long hulkFracturesFanned() { synchronized (lock) { return counters.hulkFracturesFanned; } }

    /** RAPP roster-append frames fanned to all live sessions. */
    public long rosterAppendsFanned() { synchronized (lock) { return counters.rosterAppendsFanned; } }

    /** Cumulative MOVR fans (at most one per serve, before the goodbye). */
    public long matchOversFanned() { synchronized (lock) { return counters.matchOversFanned; } }

    /** Cumulative HKPS fans (one per drained corpse-pose keyframe). */
    public long hulkPosesFanned() { synchronized (lock) { return counters.hulkPosesFanned; } }

    /** Cumulative AIMP fans (one per composed change-gated aim batch). */
    public long pilotAimsFanned() { synchronized (lock) { return counters.pilotAimsFanned; } }

    /** Cumulative HKCG fans (one per drained cut-geometry record, 4 per cut). */
    public long hulkCutGeometryFanned() { synchronized (lock) { return counters.hulkCutGeometryFanned; } }

    /** Cumulative endpoint-lifetime bytes handed to the socket, all sends incl. handshake
     * (the npeer round line's {@code bytes_sent=}). */
    public long bytesSentTotal() {
        return bytesSentTotal.get();
    }

    // ------------------------------------------------------------------ timer thread

    private void timerLoop() {
        while (!closed) {
            try {
                Thread.sleep(TIMER_GRANULARITY_MILLIS);
            } catch (InterruptedException interrupted) {
                return; // close() interrupts — endpoint lifetime over
            }
            try { // per-pass belt; Errors propagate
                // Snapshot under lock, then service each core OUTSIDE lock (lock-order rule).
                List<PeerSession> snapshot;
                synchronized (lock) {
                    snapshot = new ArrayList<PeerSession>(sessionsById.values());
                }
                for (PeerSession session : snapshot) {
                    if (session.pendingDeathOrNull() != null || session.replay) {
                        continue; // Dead sessions are wire-DEAD — no retransmits or
                                  // keepalives; the peer's silence deadline must fire.
                                  // A replayed session has no peer: no keepalive, nothing to retransmit.
                    }
                    try { // review F1: per-SESSION belt — one broken core never starves the rest
                        session.reliability.onTimer(nowMillis());
                        drainCtrlSpool(session);
                    } catch (RuntimeException fault) {
                        onTimerFault(fault);
                    }
                }
                variantPicks.sweepExpired(); // W15 v0 step 3(c): the 30 s pending-pick hold deadline
                synchronized (lock) {
                    for (PeerSession session : sessionsById.values()) {
                        if (session.byeSent && !session.byeAcked && !session.peerSaidBye) {
                            // Goodbye resend each timer tick until the peer's BYE echo:
                            // header-only, 36 B at 15 ms granularity, bounded by close() which follows
                            // shutdownOutput — simpler than a second cadence clock and terminates the
                            // moment the echo lands.
                            sendEnvelopeOnly(session, DatagramFrameCodec.WireChannel.BYE);
                        }
                    }
                }
                if (multiPeer) {
                    sweepSilenceDeadlines(snapshot);
                    emitNpeerCtrlLines(snapshot);
                }
                // LAST — a throwing roll skips nothing v12 needs
                PidsHello13 t13 = hello13;
                if (t13 != null) {
                    t13.onTimer(nowMillis());
                }
            } catch (RuntimeException fault) {
                onTimerFault(fault);
            }
        }
    }

    /** One timer-pass RuntimeException — counted
     * ({@code timer_faults=} on the door line); the FIRST is printed with its stack, later ones
     * only count. The loop keeps running so every session keeps its retransmits + silence sweep. */
    private void onTimerFault(RuntimeException fault) {
        long n = ++timerFaults;
        if (n == 1L) {
            System.out.println("pilot-datagram-server timer_fault #1 " + fault.getClass().getName()
                    + ": " + String.valueOf(fault.getMessage()).replaceAll("\\p{Cntrl}+", " ")
                    + " (later faults count only: timer_faults= on the door line)");
            for (StackTraceElement frame : fault.getStackTrace()) { // review F2: frames only —
                System.out.println("\tat " + frame);                   // printStackTrace re-prints the raw message
            }
            // The cause chain, sanitized like the
            // head (control chars stripped, message capped at 160 chars), depth-capped at 3 causes.
            Throwable cause = fault.getCause();
            for (int depth = 1; cause != null && cause != fault && depth <= 3; depth++) {
                String msg = String.valueOf(cause.getMessage()).replaceAll("\\p{Cntrl}+", " ");
                // own prefix: watch-verdict axis 17 takes the LAST 'timer_fault #1 ' line as the head
                System.out.println("pilot-datagram-server timer_fault_cause depth=" + depth + " "
                        + cause.getClass().getName() + ": "
                        + (msg.length() > 160 ? msg.substring(0, 160) : msg));
                cause = cause.getCause();
            }
        }
    }

    /**
     * N-door standing silence sweep: the retired legacy door
     * priced peer silence inside its blocking {@code receiveIntent} wait — a surface the N serve
     * loop never drove — so at N the door's OWN timer applies the same per-session deadline
     * (join grace until the first valid datagram, the steady read deadline after) against
     * {@code lastValidArrivalMillis} and fails the session with the SAME {@code udp_silence}
     * verdict token {@link #classifySilence} mints. Door-side by design: the sim thread must not
     * own wall-clock liveness. Sessions with liveness unarmed (deadline 0) are never swept.
     */
    private void sweepSilenceDeadlines(List<PeerSession> snapshot) {
        long now = nowMillis();
        synchronized (lock) {
            for (PeerSession session : snapshot) {
                if (!session.isLiveLocked()) {
                    continue;
                }
                int deadlineMillis = session.livenessReadDeadlineMillis;
                if (deadlineMillis <= 0) {
                    continue;
                }
                boolean gracePending = session.livenessJoinGracePending;
                long bound = gracePending ? session.livenessJoinGraceMillis : deadlineMillis;
                long silent = now - session.lastValidArrivalMillis;
                if (silent >= bound) {
                    failSessionLocked(session, classifySilence(session, gracePending, silent));
                }
            }
        }
    }

    /** Timer-thread spool drain. Peek→send→pop: the head leaves the spool
     * only after the core accepted it, so a concurrent {@link #ctrlSpoolOrFail} can never see an
     * empty spool while a frame is still in flight (emission order preserved); a refused head
     * retries next timer tick. */
    private void drainCtrlSpool(PeerSession session) {
        while (true) {
            byte[] head;
            synchronized (lock) {
                if (closed || session.pendingDeath != null) {
                    return;
                }
                head = session.ctrlSpool.peekFirst();
            }
            if (head == null || !session.reliability.ctrlSend(head, nowMillis())) {
                return;
            }
            synchronized (lock) {
                session.ctrlSpool.pollFirst();
            }
        }
    }

    /** Rung-0 N-door observability: one periodic line per live session — the
     * spool depth is the gauge that predicts {@code ctrl_window_stall} long before it fires.
     * Multi-door only (legacy logs stay byte-stable); timer thread only.
     * <p>W5-D5: the door counters used to surface ONLY at {@code await_timeout},
     * so a deaf-admission flight left "door drop vs never-arrived" undecidable from the
     * artifacts. One {@code pilot-datagram-door} endpoint line now rides the same cadence —
     * every field an internal numeric counter (no wire-derived text: the log law keeps player text out of every log row).
     * The counters are increment-only, so their sum is a change detector: with no sessions the
     * line still emits while the door sees traffic, and the truly-idle log stays silent. */
    private void emitNpeerCtrlLines(List<PeerSession> snapshot) {
        long now = nowMillis();
        if (now < nextNpeerCtrlLineAtMillis) {
            return;
        }
        long doorActivity = readerCounters.receivedDatagrams + readerCounters.droppedUndecodable + readerCounters.droppedMalformedHello
                + readerCounters.helloRejected + readerCounters.helloWelcomeResends + readerCounters.droppedBadSession + readerCounters.droppedBadSource
                + readerCounters.droppedDeadSession + readerCounters.reAdmissions
                + readerCounters.cookieVerified + readerCounters.attemptHeld; //
        if (snapshot.isEmpty() && doorActivity == lastDoorActivityEmitted) {
            return;
        }
        nextNpeerCtrlLineAtMillis = now + NPEER_CTRL_LINE_MILLIS;
        lastDoorActivityEmitted = doorActivity;
        VoiceRelay v = voice; // W12 L2: the relay's meter rides the same 5-s cadence (counts only)
        if (v != null) {
            System.out.println(v.meterLine());
        }
        int live = 0;
        synchronized (lock) {
            for (PeerSession session : snapshot) {
                if (session.isLiveLocked()) {
                    live++;
                }
            }
        }
        System.out.println(doorLine(live));
        emitNpeerCtrlSessionLines(snapshot);
    }

    /** The {@code pilot-datagram-door} endpoint line (every field an internal numeric counter —
     * never wire text, cookie or password bytes). Composed here for the timer cadence above AND
     * the one close-time print ({@link #close()}): a serve that ends by operator
     * {@code end-match} used to leave its final door counters unprinted (the last periodic row
     * could predate the serve's last HELLOs by up to {@value #NPEER_CTRL_LINE_MILLIS} ms —
     * {@code UpFuzzSoakContract}'s Verdict 3 FAIL was invisible to its last-row read). */
    private String doorLine(int live) {
        // The counter tokens moved verbatim
        // to PidsReaderCounters.appendDoorTokens (same order, same text).
        StringBuilder sb = new StringBuilder(1536).append("pilot-datagram-door live=").append(live);
        readerCounters.appendDoorTokens(sb, variantSubmitsReceived(), variantReceiptsSent(), timerFaults, sourceHold.size());
        return sb.toString();
    }

    /** The per-session {@code npeer-ctrl} rows that follow the door line on the timer cadence. */
    private void emitNpeerCtrlSessionLines(List<PeerSession> snapshot) {
        for (PeerSession session : snapshot) {
            int depth;
            synchronized (lock) {
                if (!session.isLiveLocked()) {
                    continue;
                }
                depth = session.ctrlSpool.size();
            }
            DatagramReliability.Stats stats = session.stats();
            System.out.println("npeer-ctrl session=" + session.sessionId()
                    + " seat=" + session.seatId()
                    + " ctrl_pending_depth=" + depth
                    + " ctrl_window_refused=" + stats.ctrlWindowRefused()
                    // Per-seat bandwidth (appended fields only — grammar rule)
                    + " tx_bytes=" + session.bytesSent()
                    + " rx_bytes=" + session.bytesReceived());
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Non-blocking CTRL send for the N serve loop: a window-full refusal
     * spools (order-preserving — a frame never overtakes an earlier spooled frame) and the timer
     * thread drains ({@link #drainCtrlSpool}); spool overflow fails the session ASYNC with the
     * SAME {@code ctrl_window_stall} verdict token as the legacy bounded block — the sim thread
     * never blocks and never throws. Dead/dying sessions are silent no-ops. Called outside
     * {@code lock} (the core send must not run under it — lock-order rule, class doc).
     */
    private void ctrlSpoolOrFail(PeerSession session, byte[] frame) {
        if (session.replay) {
            // The CTRL choke for a replayed session — no frame enters its
            // reliability window or this spool, so with no peer ever acking, the window never fills and the
            // spool bound below can never kill it (a socket-only suppression would, at CTRL_SPOOL_BOUND).
            return;
        }
        boolean tryDirect;
        synchronized (lock) {
            if (closed || !session.isLiveLocked()) {
                return;
            }
            // Order preservation: only an empty spool may be bypassed (the spool stays non-empty
            // until a peeked head is truly in the core's window — see drainCtrlSpool).
            tryDirect = session.ctrlSpool.isEmpty();
        }
        if (tryDirect && session.reliability.ctrlSend(frame, nowMillis())) {
            return;
        }
        synchronized (lock) {
            if (closed || !session.isLiveLocked()) {
                return;
            }
            if (session.ctrlSpool.size() >= CTRL_SPOOL_BOUND) {
                failSessionLocked(session, new PilotPeerDisconnectedException("ctrl_window_stall",
                        "pilot datagram CTRL spool overflow (reason token: ctrl_window_stall —"
                                + " send window full and " + CTRL_SPOOL_BOUND
                                + " frames already spooled; peer not acking)"));
                return;
            }
            session.ctrlSpool.addLast(frame);
        }
    }

    private PilotPeerDisconnectedException classifySilence(PeerSession session,
            boolean gracePending, long silentMillis) {
        if (gracePending) {
            return new PilotPeerDisconnectedException("udp_silence",
                    "pilot peer connected but never began exchanging within the join grace ("
                            + session.livenessJoinGraceMillis + " ms from admission to first datagram;"
                            + " reason token: udp_silence — " + silentMillis + " ms silent)");
        }
        return new PilotPeerDisconnectedException("udp_silence",
                "pilot peer liveness read deadline exceeded (" + session.livenessReadDeadlineMillis
                        + " ms of peer datagram silence mid-round; reason token: udp_silence — "
                        + silentMillis + " ms silent)");
    }

    /**
     * Mail the victim its KICKED notice (HOUS role 1) BEFORE the kick site
     * fails the session — the ONLY consumer-arc HOUS emission, from the TWO kick sites + the
     * wq P25 squat eviction ({@link #drainPendingEvictNotices}: AFTER its kill, the one site
     * where the kill must precede — same wire result, a dead session gets no other CTRL); never
     * generic deaths: silence stays the death contract, this is deliberate-removal
     * UX. Rides the reliability core's synchronous CTRL send (real seq space — the client
     * delivers in order exactly once) and NEVER the spool (the drain skips pendingDeath, a
     * spooled notice would die with the session). Because a failed session gets no
     * retransmits, loss redundancy = two SAME-seq raw duplicates of the frame (any one of
     * the three datagrams delivers; the client core drops the rest as dups) with receiver
     * state zeroed like the BYE envelope — ack processing is monotonic, a zero regresses
     * nothing. Best-effort: a window-full refusal or all-three loss degrades to the peer's
     * read-deadline silence path (today's behavior). Called OUTSIDE {@code lock} (core send
     * lock-order rule); the caller re-resolves + re-checks the target under its kill relock.
     */
    void sendKickedNoticeBurst(PeerSession session, int reasonCode) {
        byte[] frame = ReservedControlFrameCodec.encodeKickedNoticeFrame(reasonCode);
        long seq = session.reliability.ctrlSendReturningSeq(frame, nowMillis());
        if (seq < 0L) {
            System.out.println("pilot-datagram-server kicked_notice_refused session="
                    + session.sessionId + " seat=" + session.seatId + " reason=" + reasonCode);
            return;
        }
        byte[] datagram = DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.CTRL,
                roundId, session.sessionId, seq, 0L, 0, 0L, frame);
        sendToSession(session, datagram);
        sendToSession(session, datagram);
        WireRecordSink s = sink;
        if (s != null) {
            s.record(WireRecordSink.KIND_DOWN, frame);
        }
        System.out.println("pilot-datagram-server kicked_notice session=" + session.sessionId
                + " seat=" + session.seatId + " reason=" + reasonCode + " seq=" + seq);
    }

    private void failSession(PeerSession session, PilotPeerDisconnectedException death) {
        synchronized (lock) {
            failSessionLocked(session, death);
        }
    }

    void failSessionLocked(PeerSession session, PilotPeerDisconnectedException death) {
        if (session.pendingDeath == null) {
            session.pendingDeath = death;
            voiceRebuildLiveLocked(); // W12 L2: fail = live-set mutation site 3 of 4
            // Kills were invisible in stdout (the reason lived only on the
            // exception; the churn kill was diagnosed by experiment). One row per death,
            // at the moment it happens, machine-token reason; bounded by admissions (the
            // admission budget) — never by peer bytes.
            System.out.println("pilot-datagram-server session_fail session=" + session.sessionId
                    + " seat=" + session.seatId + " reason=" + death.reasonToken()
                    + " from=" + session.peer);
        }
        // Session death frees the seat (death-contract RESEAT); the fine-grained
        // reason token stays on the exception — the SEAT stream cause is uniformly SILENCE.
        vacateSessionSeatLocked(session, SeatStateCause.VACATE_SILENCE);
        lock.notifyAll();
    }

    /** Lock held. Release the session's seat claim, once (idempotent — vacate of an already-free
     * token is a no-op, and the null smoke door never binds a token). Uniformly cause-aware:
     * legal unarmed, where it degrades to the plain release — zero legacy drift;
     * armed (N serve, step 5) every release reaches the SEAT event stream. The ledger's own
     * monitor never calls back into the endpoint, so taking it under {@code lock} is safe. */
    void vacateSessionSeatLocked(PeerSession session, SeatStateCause cause) {
        if (seatAuthority != null && session.seatToken != null && !session.seatVacated) {
            if (cause != SeatStateCause.VACATE_DISPLACED) {
                // Remember whether the dying session ever established two-way
                // — the grace lane's toast gate reads it at ENTER_GRACE (lastVacateReachedTwoWay).
                lastTrueVacateTwoWayBySeat.put(session.seatId, session.reachedTwoWay);
            }
            // A ban's release = the same SEAT record, no grace hold.
            if (session.bannedVacate) {
                seatAuthority.vacateBanned(session.seatToken);
            } else {
                seatAuthority.vacate(session.seatToken, cause);
            }
            queueGodModeOffLocked(session.seatId); // The latch dies with the seat
            if (passwordLedger != null) {
                // The cid's seat claim frees exactly when the token occupancy does
                // (death / kick / BYE / displacement) — idempotent both, one flag. The takeover
                // pre-release deliberately does NOT come through here: there the ledger claim
                // survives into the successor (resolvePasswordSeatLocked).
                passwordLedger.vacate(session.clientId);
                // The seat's team goes with it; the player's remembered
                // team stays for the rejoin (every true vacate funnels here, the squat eviction included).
                seatTeams.vacate(session.seatId, session.clientId);
            }
            // W2b E4: a TRUE vacate (never displacement — there the seat
            // survives into the successor, mount included) queues for the serve loop, which
            // deposits into the arbiter with NO lock held (the never-nested lock law); a dead
            // session's mounted module still gets its dismount (the ghost-module law).
            if (moduleSeats != null && cause != SeatStateCause.VACATE_DISPLACED) {
                trueSeatVacates.add(session.seatId);
            }
            session.seatVacated = true;
        }
    }

    /** Take-and-clear the queued true seat vacates (W2b E4) — the caller touches the arbiter
     * only AFTER this returns, endpoint lock released. */
    public List<Integer> takeTrueSeatVacates() {
        synchronized (lock) {
            if (trueSeatVacates.isEmpty()) {
                return List.of();
            }
            List<Integer> taken = List.copyOf(trueSeatVacates);
            trueSeatVacates.clear();
            return taken;
        }
    }

    /** Fail-closed guard for the N-only members. Vacuously true since the
     * legacy-door delete (every open path is multi-peer); kept as the seam guard. */
    private void requireMultiPeerDoor(String member) {
        if (!multiPeer) {
            throw new IllegalStateException(member + " is multi-peer-shaped — every door is"
                    + " multi-peer since the legacy-door delete");
        }
    }

    private static void requireLivenessKnobs(int readDeadlineMillis,
            int declarationBudgetPerExchange, long joinGraceMillis) {
        if (readDeadlineMillis < 0 || declarationBudgetPerExchange < 0 || joinGraceMillis < 0L) {
            throw new IllegalArgumentException(
                    "liveness knobs must be >= 0 (0 = off); got readDeadlineMillis="
                            + readDeadlineMillis + " declarationBudgetPerExchange="
                            + declarationBudgetPerExchange + " joinGraceMillis=" + joinGraceMillis);
        }
    }

    private void waitOnLock(long millis, String interruptMessage) throws IOException {
        try {
            lock.wait(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interruptMessage, interrupted);
        }
    }

    static int payloadMagic(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES) {
            return 0;
        }
        return ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
    }

    /** ONE monotonic clock for the whole endpoint (the reliability cores require one source; the voice relay's pump reads it too). */
    public static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
