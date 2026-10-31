package cc.starsector.client.net;

import cc.starsector.client.FallbackAnnounce;
import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.Handshake13;
import cc.starsector.server.runtime.Handshake13Flight;
import cc.starsector.server.runtime.Handshake13PairBudget;
import cc.starsector.server.runtime.HandshakeCodec;
import cc.starsector.server.runtime.HandshakeCrypto;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The version-13 re-HELLO pump's flight slots for ONE {@link PredictReconcileDatagramSession}.
 * WHY a class of its own: a
 * version-13 session must re-admit itself (silence, a dead receive, a mission re-entry) without ever putting the match
 * password or seat token on the wire readable — so its re-HELLO is a {@link Handshake13Flight}, ONE per nonce
 * GENERATION (an episode arm or a W5-D5 escalation mints a generation; a resend re-sends the flight's identical
 * bytes) — and the slot bookkeeping that keeps those flights apart is kept out of the 3,400-line session. Every
 * session builds one (THE FLIP: the kit speaks version 13 only); the session's re-HELLO path hangs off
 * that one final. The "version-12 twin" notes below name the deleted version-12 path each rule replaced.
 *
 * <p><b>The slots</b> (every one guarded by the session's {@code lock}; this class holds NO lock of its own, and each
 * method that touches a slot is a {@code …Locked} method whose CALLER holds that lock):
 * <ul>
 *   <li>CURRENT — the generation's flight (the pump resends it; a WELCOME is offered to it first);</li>
 *   <li>PREVIOUS — the one older generation still listened to (the version-12 B2 twin: a WELCOME already on the wire
 *       when the generation rotated is still OUR admission). LISTEN-ONLY: it hears
 *       ADMITTED13 / REJECTED only and never seals, so ONE flight seals per session at a time. WHY: a PREVIOUS offered
 *       a {@code COOKIE13} would seal and send a fresh-nonce F2 — a second admission racing CURRENT's for one
 *       session-table key, which can leave the kit adopted on a session the door has already displaced. A
 *       {@code COOKIE13} that echoes one of PREVIOUS's nonces is handed to CURRENT instead (r2;
 *       {@link Offer#run}): the door's answer to PREVIOUS's last send carries the unit a changed path needs, and
 *       CURRENT seals against it under its own cap and budget — the version-12 twin stores that cookie for its
 *       current HELLO;</li>
 *   <li>KEPT (≤ 2) — while LIVE: the flight that adopted (a NEWER seal of it can still be ADMITTED, item 73 (α)) plus
 *       CURRENT when the adopt came through PREVIOUS; seeded ONCE with the dial's flight (item 19 (4)); a LIVE
 *       late adopt through the second retires the first ({@link #lateGenerationLocked});</li>
 *   <li>the RETIRED list — flights detached from every slot, each abandoned by the TIMER thread OUTSIDE the lock;</li>
 *   <li>the HELD unit — the latest {@code COOKIE13} unit a flight sealed against on this socket (the version-12
 *       {@code reconnectCookie} twin; a socket recreate drops it). {@link Handshake13.Cookie13} carries arrays, but
 *       {@code heldCookieOrNull()} hands out a copy and {@code adoptCookie} copies in, so the mirror is never
 *       shared with a flight;</li>
 *   <li>BUILD-PENDING + a slot GENERATION stamp: an arm / escalation / adopt / exit bumps it, and a build whose
 *       snapshot stamp no longer matches is never published.</li>
 * </ul>
 *
 * <p><b>Threads</b> (the flight's CALLER LAW: X25519 under a session lock or on the frame thread is forbidden). No
 * {@link Handshake13Flight} call runs under the session lock or on any thread but the session's timer and reader. The
 * TIMER builds ({@link #build}: constructor + {@code adoptCookie}; the session calls {@code start}), resends
 * ({@code retransmitOrNull}), retires ({@link #abandonAll}) and reads the dial's {@code heldCookieOrNull()} once at its
 * first pass; the READER runs an {@link Offer} ({@code onWelcome}, and for a challenge that answered PREVIOUS
 * {@code siblingChallengeOrNull} / {@code onSiblingChallenge}; the first flight's {@code sealCapped()} before it and after a
 * HELD, the HELD-cause delta) and, right after a {@code SEND}, reads that flight's {@code heldCookieOrNull()}. An arm and
 * {@code close()} (either can be the frame thread) only move references, and so does the dial thread's KEPT seed at
 * its hand-off ({@link #seedLocked}, before the reader starts).
 *
 * <p><b>ACCEPTED race</b> (no lock added for it): a slot move on another thread between the timer's
 * snapshot and its outside-lock {@code retransmitOrNull()} — or between a publish and the BUILD's send, or between the
 * reader's snapshot and its {@code SEND} — lets ONE datagram of a just-detached flight leave. The door's mint for it
 * is taken over by the fresh flight's HELLO (an F2 spends the door's 250 ms lane gap, so the fresh F2 may be held
 * once), and its WELCOME13 arrives here as a counted duplicate. HOLD BOUND (the b1b): after a
 * re-arm the resend interval is back at 250 ms, so the hold is one gap; in the ESCALATION race the fresh flight seals at
 * {@code start} and is resent at the generation's interval, so the hold lasts up to that interval — at most the
 * session's 1 s resend cap, once.
 *
 * <p><b>The seal cap</b> ({@code Handshake13Flight}'s class javadoc, The cap): a flight seals at most
 * {@code SEAL_CAP} F2s on challenges alone, then one per timer resend of it. Only CURRENT is resent and only CURRENT
 * seals (PREVIOUS is listen-only; a challenge that answered it is CURRENT's seal, counted in
 * CURRENT's cap), so a PREVIOUS seals nothing; under a stream answering
 * every F2, a generation whose threshold its burst cannot reach (16) is paced by the timer's resends, ~1 s each.
 *
 *
 * <p>VALUE-DOMAIN: {@code shipId >= 0} (0 = AUTO / observer); the generation stamp counts up from 0 and is compared
 * for equality only; the held flags = {@code 0} or {@code Handshake13.FLAG_OBSERVER}, each build ORs in the MagicLib
 * report bits ({@code MagicCapabilities.helloFlagBits()}, a subset of 0x18).
 *
 *
 */
final class Hs13ReHelloFlights {

    private static final int KEPT_MAX = 2;

    /** The owning session: its {@code lock}, episode state and counters (package-private there, for this class). */
    private final PredictReconcileDatagramSession s;
    // The build values: the SAME the version-12 re-HELLO encodes (PRDS pumpReHello), held in the dial's order.
    private final Handshake13Flight.Lane lane;
    private final String seatToken;
    private final int flags;
    private final String print;
    private final String cid;
    private final long shipId;
    private final String variantId;
    /** The pilot's 16-byte control keymap (a private copy; null = none = the serve's own bindings), re-sealed by EVERY
     *  re-HELLO: a re-HELLO mints a NEW serve session, so zeros here would silently revert the seat to the serve's
     *  bindings. Seeded with the dial's game-thread read, REPLACED by {@link #setKeymap} at every combat init (the
     *  same resolution the sampler's control bits poll), never re-resolved here (the timer thread never reads the
     *  engine). Volatile: written on the game thread, read by the timer's {@link #build}.
     *  main-written */
    private volatile byte[] keymap;
    private final InetSocketAddress server;
    private final SecureRandom rng;

    private final Handshake13Flight[] kept = new Handshake13Flight[KEPT_MAX];
    private Handshake13Flight current;
    private Handshake13Flight previous;
    private final ArrayList<Handshake13Flight> retired = new ArrayList<>();
    private Handshake13.Cookie13 heldUnit;
    private boolean buildPending;
    private long generation;
    private boolean seeded;
    /** Written under the lock with {@link #retired}; read lock-free by the LIVE timer tick (skip the lock when idle). */
    private volatile boolean retirePending;
    /** The {@code /auth} context of the session the PRDS currently speaks for (guarded by the PRDS lock, like the
     *  slots): seeded by the dial, swapped by {@link #setAuthContextLocked} in the SAME lock hold as each adopt that
     *  succeeded, so {@code {context, reliability core}} always read as one pair. {@code null} = no proof possible. */
    private Auth13Context auth;

    /**
     * The version-13 {@code /auth} inputs of ONE admitted session: the
     * session root and the door's per-admit auth share {@code YbAuth}, both from the opened WELCOME13. WHY a holder:
     * {@code PRDS.sendAuth13} must read the context and the reliability core in ONE lock hold — a proof minted for one
     * session must never enter another session's core. Immutable private copies (the record's arrays stay the
     * flight's); never logged, and {@code toString} is constant text. VALUE-DOMAIN: both arrays are exactly 32 B.
     *
     */
    static final class Auth13Context {
        private final byte[] root;
        private final byte[] ybAuth;

        private Auth13Context(byte[] root, byte[] ybAuth) {
            this.root = root;
            this.ybAuth = ybAuth;
        }

        /** {@code null} for a null admission or a root / share that is not 32 B (the proof then reads UNAVAILABLE). */
        static Auth13Context ofOrNull(Handshake13.Admitted13 admitted) {
            byte[] root = admitted == null ? null : admitted.sessionRoot();
            byte[] share = admitted == null ? null : admitted.ybAuth();
            if (root == null || root.length != HandshakeCrypto.KEY_BYTES || share == null
                    || share.length != HandshakeCrypto.POINT_BYTES) {
                return null;
            }
            return new Auth13Context(root.clone(), share.clone());
        }

        byte[] root() {
            return root;
        }

        byte[] ybAuth() {
            return ybAuth;
        }

        /** Constant text: never a root or share byte. */
        @Override
        public String toString() {
            return "Hs13ReHelloFlights.Auth13Context";
        }
    }

    /** Caller holds the PRDS lock: the dial's seed (before the daemons start) or an adopt that SUCCEEDED, in the
     *  adopt's own lock hold. A refused / rejected adopt never calls it, so the old context stays with the old core. */
    void setAuthContextLocked(Handshake13.Admitted13 admitted) {
        auth = Auth13Context.ofOrNull(admitted);
    }

    /** Caller holds the PRDS lock (read together with the reliability core). */
    Auth13Context authContextLocked() {
        return auth;
    }

    /**
     * Built in the session's constructor ({@code session} is only stored here, never called before its daemons
     * start). {@code print} / {@code cid} = "" when the session holds none (the version-12 encode's own substitution).
     */
    Hs13ReHelloFlights(PredictReconcileDatagramSession session, Handshake13Flight.Lane lane, String seatToken,
            boolean observer, String print, String cid, long shipId, String variantId, byte[] keymapOrNull,
            InetSocketAddress server, SecureRandom rng) {
        this.s = session;
        this.lane = lane;
        this.seatToken = seatToken;
        this.flags = observer ? Handshake13.FLAG_OBSERVER : 0;
        this.print = print == null ? "" : print;
        this.cid = cid == null ? "" : cid;
        this.shipId = shipId;
        this.variantId = variantId;
        this.keymap = keymapOrNull == null ? null : keymapOrNull.clone();
        this.server = server;
        this.rng = rng;
    }

    /**
     * The GAME thread, at combat init, BEFORE the kept-open takeover re-HELLO is armed ({@code armReAdmission}): the
     * map that init resolved — the SAME array the sampler's control bits are polled from — becomes the map every
     * later re-HELLO seals. WHY: an app-scoped session stays open across ESC, a rebind at Settings and a re-pick; its
     * takeover re-HELLO used to re-seal the DIAL-time map while the sampler polled the new bindings, so a hull
     * script's literal key read answered HELD on the serve for a key the pilot no longer holds.
     * {@code null} = none (the serve's own bindings); a non-null map must be 16 lawful bytes (the flight's own law,
     * checked HERE on the game thread so the timer never dies of it). A private copy is kept.
     * Main-written
     */
    void setKeymap(byte[] keymapOrNull) {
        if (keymapOrNull != null && !Handshake13.keymapLawful(keymapOrNull)) {
            throw new IllegalArgumentException("keymap");
        }
        keymap = keymapOrNull == null ? null : keymapOrNull.clone();
    }

    /**
     * Rule 1 (caller holds the PRDS lock): ONCE — the dial's flight becomes the first KEPT flight; separately, the
     * dial's held unit (the timer's first-pass read; {@code null} from the dial's hand-off or an arm) fills the mirror
     * when it is still unset. The FIRST call is the dial's hand-off ({@code PredictDatagramDial.admit}, before the
     * reader starts); a later call with the same flight changes no slot.
     */
    void seedLocked(Handshake13Flight dial, Handshake13.Cookie13 dialUnit) {
        if (!seeded) {
            seeded = true;
            kept[0] = dial;
        }
        if (dialUnit != null && heldUnit == null) {
            heldUnit = dialUnit;
        }
    }

    /**
     * Rule 2, an episode ARM (caller holds the PRDS lock; any thread): seed first, then every slot flight — KEPT,
     * CURRENT, PREVIOUS — is retired and a build is pending. No flight call. Leaves KEPT empty, and nothing refills it
     * while reconnecting (the seed is once; only {@link #keepAdopterLocked} writes it).
     */
    void detachAllLocked(Handshake13Flight dial) {
        seedLocked(dial, null);
        for (int i = 0; i < KEPT_MAX; i++) {
            retireLocked(kept[i]);
            kept[i] = null;
        }
        detachPendingLocked();
        buildPending = true;
    }

    /**
     * Rule 3, the W5-D5 escalation twin (caller holds the PRDS lock; the timer): PREVIOUS retired, PREVIOUS ← CURRENT,
     * CURRENT empty, a build pending; a socket recreate drops the held unit (it is bound to the old source port).
     */
    void escalateLocked(boolean socketRecreated) {
        retireLocked(previous);
        previous = current;
        current = null;
        buildPending = true;
        generation++;
        if (socketRecreated) {
            heldUnit = null;
        }
    }

    /** Rule 6 (caller holds the PRDS lock): reconnecting ended WITHOUT an adopt — CURRENT + PREVIOUS retired. */
    void detachPendingLocked() {
        retireLocked(current);
        retireLocked(previous);
        current = null;
        previous = null;
        buildPending = false;
        generation++;
    }

    /**
     * Rule 3 on a DUE pump tick (caller holds the PRDS lock; the timer): BUILD when one is pending (+ the held-unit
     * snapshot), else RESEND of CURRENT, else {@code null} (nothing to send this tick).
     */
    Step stepLocked() {
        if (buildPending) {
            return new Step(null, heldUnit, generation);
        }
        return current == null ? null : new Step(current, null, generation);
    }

    /**
     * Rule 3 PUBLISH (caller holds the PRDS lock and has checked {@code reconnecting && !closed}): the built flight
     * becomes CURRENT only when no arm / escalation / adopt / exit moved the slots since {@code step} was taken.
     */
    boolean publishLocked(Handshake13Flight built, Step step) {
        if (step.generation != generation) {
            return false;
        }
        current = built;
        buildPending = false;
        return true;
    }

    /** Rule 4 (c) / 5 (caller holds the PRDS lock): reconnecting = CURRENT then PREVIOUS; LIVE = the KEPT flights. */
    Offer offerLocked(boolean live) {
        return live ? new Offer(kept[0], kept[1], false) : new Offer(current, previous, true);
    }

    /** Caller holds the PRDS lock: {@code flight} is still CURRENT or PREVIOUS. */
    boolean offeredLocked(Handshake13Flight flight) {
        return flight != null && (flight == current || flight == previous);
    }

    /** Rule 4 (d) (caller holds the PRDS lock): the unit the reader's SEND sealed against — non-null only. */
    void holdLocked(Handshake13.Cookie13 unit) {
        if (unit != null) {
            heldUnit = unit;
        }
    }

    /**
     * Rule 4 (f) (caller holds the PRDS lock; the adopt succeeded): KEPT = the adopter, plus CURRENT when the adopter
     * was PREVIOUS; an adopt through CURRENT retires PREVIOUS; CURRENT and PREVIOUS end empty. KEPT was emptied by the
     * episode's arm ({@link #detachAllLocked}), so nothing is overwritten here.
     */
    void keepAdopterLocked(Handshake13Flight adopter) {
        kept[0] = adopter;
        if (adopter == previous) {
            kept[1] = current;
        } else {
            retireLocked(previous);
        }
        current = null;
        previous = null;
        buildPending = false;
        generation++;
    }

    /** Lock-free: something waits to be abandoned (the LIVE timer tick takes the lock only then). */
    boolean retirePending() {
        return retirePending;
    }

    /** Every timer tick at version 13 (caller holds the PRDS lock): the retired flights, or {@code null} when none. */
    List<Handshake13Flight> drainLocked() {
        if (retired.isEmpty()) {
            return null;
        }
        List<Handshake13Flight> out = new ArrayList<>(retired);
        retired.clear();
        retirePending = false;
        return out;
    }

    /**
     * Rule 6, the TIMER thread's exit (caller holds the PRDS lock): every slot, the retired list and the dial's flight;
     * the slots stay empty (seeded, so no later seed re-adds the dial; no arm passes {@code closed} / a latched failure).
     */
    List<Handshake13Flight> takeAllLocked(Handshake13Flight dial) {
        seeded = true;
        for (int i = 0; i < KEPT_MAX; i++) {
            retireLocked(kept[i]);
            kept[i] = null;
        }
        detachPendingLocked();
        retireLocked(dial);
        return drainLocked();
    }

    /** ROW SEAM (the selftest rows only — product code never calls it): the slots, snapshotted under
     * the session's lock as {@code {current, previous, kept[0], kept[1]}}, each possibly null. References only — no
     * flight call runs here, so the CALLER LAW holds; a row reads {@code toString()} / {@code seals()} on its own
     * thread. */
    Handshake13Flight[] slotsForRows() {
        synchronized (s.lock) {
            return new Handshake13Flight[] {current, previous, kept[0], kept[1]};
        }
    }

    private void retireLocked(Handshake13Flight flight) {
        if (flight != null && !retired.contains(flight)) {
            retired.add(flight);
            retirePending = true;
        }
    }

    /**
     * Rule 3 BUILD — the TIMER thread, OUTSIDE the PRDS lock: a fresh flight with the version-12 re-HELLO's values in
     * the dial's order and the dial's per-door budget bucket; {@code adoptCookie} of the step's held-unit snapshot when
     * non-null. The caller calls {@code start}. The temporary secret array is zeroed (the flight keeps its own copy).
     * A {@code RuntimeException} here is the timer-death belt's (session terminal; the timer's exit abandons the
     * slots — a flight built but not yet in one is abandoned HERE and in {@link #send} before the rethrow).
     *
     * <p>THE ONE-SHOT TEAM BYTE: a process sends its team pick on its FIRST HELLO only; every re-HELLO built here
     * seals team 0 = "the server's memory" (the door keeps each cid's team), so a nonzero byte at the door is always
     * an explicit pick from the join window and a re-dial can never revert a team switch made in game.
     *
     *
     * <p>THE KEYMAP IS NOT ONE-SHOT: unlike the team, the door keeps no per-cid memory of it (the map an admit takes is
     * its own F2's), so every re-HELLO re-seals the session's {@link #keymap} — the dial's read until the first combat
     * init, then that init's ({@link #setKeymap}).
     *
     */
    Handshake13Flight build(Step step) {
        byte[] secret = Handshake13.secretBytes(seatToken);
        try {
            // The MagicLib report bits are read at EVERY build, never held from
            // construction, so a re-HELLO reports what the memo says now.
            int helloFlags = flags | cc.starsector.client.magic.MagicCapabilities.helloFlagBits();
            Handshake13Flight flight = new Handshake13Flight(lane, secret, helloFlags, print, cid, shipId, variantId, 0,
                    keymap, Handshake13PairBudget.process().host(PredictDatagramDial.budgetHostKey(server)), rng);
            if (step.unit != null) {
                try {
                    flight.adoptCookie(step.unit);
                } catch (RuntimeException refused) {
                    flight.abandon(); // in no slot yet, so the timer's exit could not reach it (the b1b)
                    throw refused;
                }
            }
            return flight;
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /** The TIMER thread, OUTSIDE the PRDS lock: {@code abandon()} each ({@code null} = none; idempotent, never throws). */
    static void abandonAll(List<Handshake13Flight> flights) {
        if (flights != null) {
            for (Handshake13Flight flight : flights) {
                flight.abandon();
            }
        }
    }

    // --- the session-facing halves (each takes the session's lock itself around the …Locked calls) ---------------

    /**
     * ROW SEAM — the selftest rows ONLY; {@code null} in production (no product code writes it): run at the top of
     * {@link #timerFirstPass} when set. WHY: row T15 ({@code Hs13PumpEdgeRows}) holds the timer's first pass so the
     * reader meets a newer-seal WELCOME13 FIRST — the one order in which the dial's KEPT seed
     * ({@code PredictDatagramDial.admit}) is observable, since the first pass also seeds (once). A plain
     * {@link Runnable}: no reflection in the mod jar. Delete trigger: row T15's own deletion.
     *
     *
     */
    static volatile Runnable timerFirstPassHookForRows;

    /** Rule 1, the TIMER's first pass (before any BUILD): the rows-only {@link #timerFirstPassHookForRows} when set,
     * then the dial's held unit read OUTSIDE the lock (it answers after {@code abandon()} too), then the mirror under it
     * (the KEPT seed already ran at the dial's hand-off). */
    void timerFirstPass() {
        Runnable hook = timerFirstPassHookForRows;
        if (hook != null) {
            hook.run(); // rows only (T15): holds this pass; null in production
        }
        Handshake13Flight dial = s.dialFlight;
        Handshake13.Cookie13 unit = dial == null ? null : dial.heldCookieOrNull();
        synchronized (s.lock) {
            seedLocked(dial, unit);
        }
    }

    /** Rule 6, the TIMER's exit ({@code timerLoopBelted}'s {@code finally}): every flight taken under the lock,
     * abandoned outside it. */
    void timerExit() {
        List<Handshake13Flight> all;
        synchronized (s.lock) {
            all = takeAllLocked(s.dialFlight);
        }
        abandonAll(all);
    }

    /**
     * Rule 3's out-of-lock half (TIMER thread): BUILD = a fresh flight started, then PUBLISHED as CURRENT under the
     * lock only while the episode is live ({@code reconnecting && !closed}) and the slots did not move since the
     * step — else abandoned unsent; RESEND = CURRENT's identical bytes. A null datagram sends nothing; no version-12
     * byte is ever built here (item 44).
     */
    void send(Step step) {
        byte[] datagram;
        if (step.resend == null) {
            Handshake13Flight built = build(step);
            try {
                datagram = built.start(PredictReconcileDatagramSession.nowMillis());
            } catch (RuntimeException broken) {
                built.abandon(); // built but unpublished: in no slot, so the timer's exit could not reach it (F1)
                throw broken;
            }
            boolean published;
            synchronized (s.lock) {
                published = s.reconnecting && !s.closed && publishLocked(built, step);
            }
            if (!published) {
                built.abandon();
                return;
            }
        } else {
            datagram = step.resend.retransmitOrNull();
        }
        if (datagram != null) {
            s.sendRawDatagram(datagram);
        }
    }

    /**
     * Rule 4 — version 13's {@code adoptReconnectWelcome} (READER thread, reconnecting). The first WELCOME of an episode
     * announces once (the source, {@code status=} the routing peek, which flight answered — never payload bytes, never
     * a nonce); the source gate runs BEFORE any flight sees the payload; CURRENT then ONE PREVIOUS hear it OUTSIDE the
     * lock — but a {@code COOKIE13} is CURRENT's alone to seal (PREVIOUS is listen-only: a challenge
     * echoing only PREVIOUS's nonce is CURRENT's {@code SEND} when its unit is new to CURRENT — r2, {@link Offer#run} —
     * else a counted duplicate). SEND = the F2 leaves at once (the ACCEPTED race, class javadoc), then — the flight still offered, the
     * episode live — the held unit, the give-back and the pump's own send accounting ({@code accountReHelloSendLocked},
     * the pulled-forward send it replaces). ADMITTED / REJECTED re-check {@code closed} (drop), the slot (else a
     * duplicate) and {@code reconnecting} (false = rule 5's test), then the version-12 terminals or the adopt. Of an
     * {@code Admitted13} the opened welcome is read, and — only as {@code /auth} inputs, swapped into the auth context
     * in the adopt's own lock hold when the adopt succeeded — the auth share and the root; item 30:
     * the kind stays unread.
     */
    void onReconnectWelcome(DatagramFrameCodec.Frame frame, SocketAddress source) {
        byte[] payload = frame.payload();
        long seen = ++s.episodeWelcomesSeen;
        s.lastWelcomeSource = String.valueOf(source);
        s.lastWelcomeStatus = Handshake13.statusOrNegative(payload);
        Offer offer = null;
        if (source.equals(server)) {
            synchronized (s.lock) {
                offer = offerLocked(false);
            }
            offer.run(payload, PredictReconcileDatagramSession.nowMillis()); // PREVIOUS never seals
        }
        if (seen == 1L) {
            FallbackAnnounce.announceOnce("re-hello-first-welcome#ep" + s.reHelloEpisodes,
                    "first WELCOME of episode " + s.reHelloEpisodes + " source=" + source + " source_is_server="
                            + (offer != null) + " status=" + s.lastWelcomeStatus + " flight="
                            + (offer == null ? "unoffered" : offer.label()));
        }
        if (offer == null) {
            s.droppedBadSource++;
            return;
        }
        Handshake13Flight flight = offer.answered();
        Handshake13Flight.Event.Kind kind = offer.kind();
        if (kind == Handshake13Flight.Event.Kind.IGNORE) {
            s.welcomeDuplicates++; // every offered flight ignored it: stale, foreign, forged, a version-12 reply
            return;
        }
        if (kind == Handshake13Flight.Event.Kind.SEND) {
            s.sendRawDatagram(offer.event().datagram());
            Handshake13.Cookie13 unit = flight.heldCookieOrNull();
            synchronized (s.lock) {
                if (s.reconnecting && !s.closed && offeredLocked(flight)) {
                    s.reHelloCookieChallenges++;
                    // r2 = CURRENT sealed a challenge answering PREVIOUS
                    if (offer.viaSibling()) {
                        s.reHelloSiblingSeals++;
                    }
                    holdLocked(unit);
                    s.giveBackLocked();
                    // Version 12's fresh COOKIE
                    // pulls the pump forward and the due block tests the escalation BEFORE its send. This F2 already
                    // left, so a generation ALREADY at its threshold pulls the pump forward AFTER the accounting —
                    // else a COOKIE13 per packet (a rebinding NAT, a stale-F2 stream) keeps pushing the due block one
                    // interval out and the W5-D5 escalation + the B3 recreate never fire.
                    boolean escalationDue = s.reconnectNonceSends >= s.reconnectEscalateAfterSends;
                    s.accountReHelloSendLocked();
                    if (escalationDue) {
                        s.nextReHelloAtMillis = PredictReconcileDatagramSession.nowMillis();
                    }
                }
            }
            return;
        }
        if (kind == Handshake13Flight.Event.Kind.HELD) {
            // the budget refused the pair, or the flight is at its SEAL_CAP (its next timer resend lifts it by one):
            // nothing sent, and the generation escalates on the timer's own cadence.
            // Counted by cause (Offer.heldByCap), reader thread only
            if (offer.heldByCap()) {
                s.reHelloHeldSealCap++;
            } else {
                s.reHelloHeldBudget++;
            }
            return;
        }
        Handshake13.Admitted13 admitted = kind == Handshake13Flight.Event.Kind.ADMITTED ? offer.event().admitted() : null;
        HandshakeCodec.WelcomePayload welcome = admitted != null ? admitted.welcome() : offer.event().rejected();
        String replay;
        synchronized (s.lock) {
            if (s.closed) {
                return;
            }
            if (!offeredLocked(flight)) {
                s.welcomeDuplicates++; // detached since the snapshot (an escalation's PREVIOUS, an exit)
                return;
            }
            if (!s.reconnecting) {
                if (!lateGenerationLocked(admitted, flight)) {
                    return;
                }
                replay = "late-gen";
            } else {
                if (s.refuseReAdmissionLocked(welcome)) {
                    return; // REJECTED (+ banned re-latch) / session 0 / schema: terminal, slots retired
                }
                if (offer.viaSecond()) {
                    s.prevGenerationAdopts++;
                }
                s.adoptWelcomeLocked(welcome);
                if (s.sessionFailure != null) {
                    return; // the adopt tail refused (observer mismatch): terminal, slots retired there
                }
                setAuthContextLocked(admitted); // the new core's context, same lock hold
                keepAdopterLocked(flight);
                replay = "re-admit";
            }
        }
        s.replayAttachFramesAfterAdopt(replay); // outside the lock (the session's lock-order rule)
    }

    /**
     * Rule 5 — version 13's {@code adoptLateGenerationWelcome} (READER thread, LIVE): the source gate first; ONLY a
     * payload whose routing peek is ADMITTED is offered to the KEPT flights (snapshot under the lock, offered outside),
     * so nothing is sent while LIVE and no COOKIE13 / REJECTED reaches a flight (none seals, spends a budget token or
     * ends); every other payload is a counted duplicate. No twin of version 12's {@code reHelloEpisodes == 0} guard:
     * the dial's flight is the first kept flight.
     */
    void onLiveWelcome(DatagramFrameCodec.Frame frame, SocketAddress source) {
        if (!source.equals(server)) {
            s.droppedBadSource++;
            return;
        }
        byte[] payload = frame.payload();
        Offer offer = null;
        if (Handshake13.statusOrNegative(payload) == HandshakeCodec.WELCOME_ADMITTED) {
            synchronized (s.lock) {
                offer = offerLocked(true);
            }
            offer.run(payload, PredictReconcileDatagramSession.nowMillis()); // only ADMITTED reaches it
        }
        boolean adopted;
        synchronized (s.lock) {
            boolean opened = offer != null && offer.kind() == Handshake13Flight.Event.Kind.ADMITTED;
            adopted = lateGenerationLocked(opened ? offer.event().admitted() : null, opened ? offer.answered() : null);
        }
        if (adopted) {
            s.replayAttachFramesAfterAdopt("late-gen");
        }
    }

    /**
     * Rule 5's test (caller holds the PRDS lock): an opened admission ({@code null} = none) whose welcome names a
     * non-zero session other than ours and passes the late-gen schema compare re-adopts while LIVE, not closed and not
     * kicked ({@code adoptLateGenerationLocked}: the counter, the announce, the adopt); anything else is a counted
     * duplicate. An adopt that succeeded swaps the {@code /auth} context in this same lock hold.
     * {@code answered} = the flight that opened {@code admitted} ({@code null} with it). {@code true} = adopted (the
     * caller replays the attach frames outside the lock).
     *
     * <p>An adopt answered by {@code kept[1]} — the NEWER generation, KEPT
     * holds two flights only after an adopt through PREVIOUS — retires {@code kept[0]} in this same lock hold and
     * leaves KEPT = {the adopter}: the LIVE twin of {@link #keepAdopterLocked}'s "an adopt through CURRENT retires
     * PREVIOUS". WHY: PREVIOUS is listen-only, so every seal of the older flight reached the door
     * before the newer flight's; once the newer one is admitted, a later-arriving WELCOME13 for an older-flight seal
     * names a session the door already displaced — offered to that flight it would re-adopt it, and the kit would sit
     * on a dead session until {@code ctrl_rto} or the silence deadline. The retired flight is abandoned by the timer
     * outside the lock ({@link #drainLocked}); while LIVE no KEPT flight is ever resent.
     */
    boolean lateGenerationLocked(Handshake13.Admitted13 admitted, Handshake13Flight answered) {
        HandshakeCodec.WelcomePayload welcome = admitted == null ? null : admitted.welcome();
        if (s.reconnecting || s.closed || s.kickedTerminal || welcome == null || welcome.sessionId() == 0L
                || welcome.sessionId() == s.sessionId
                || PredictDatagramDial.schemaCompareAtAdmission(welcome.serveSchemaMax(), "late-gen") != null) {
            s.welcomeDuplicates++;
            return false;
        }
        if (!s.adoptLateGenerationLocked(welcome)) {
            return false;
        }
        setAuthContextLocked(admitted);
        if (answered != null && answered == kept[1]) {
            retireLocked(kept[0]);
            kept[0] = answered;
            kept[1] = null;
        }
        return true;
    }

    /** One due pump tick's action: {@code resend == null} = BUILD (with {@code unit}), else RESEND of that flight. */
    static final class Step {
        final Handshake13Flight resend;
        final Handshake13.Cookie13 unit;
        final long generation;

        private Step(Handshake13Flight resend, Handshake13.Cookie13 unit, long generation) {
            this.resend = resend;
            this.unit = unit;
            this.generation = generation;
        }
    }

    /**
     * One WELCOME-channel payload offered to a slot snapshot (taken under the PRDS lock, {@link #run} OUTSIDE it on
     * the READER thread): {@code onWelcome} on the first flight, and on {@code IGNORE} (or no first) on the second —
     * unless the payload is a {@code COOKIE13}, which the SECOND flight never gets to seal (PREVIOUS is listen-only,
     * rule 4). The offer reads the payload's own routing peek for that, never a caller's
     * flag.
     */
    static final class Offer {
        private final Handshake13Flight first;
        private final Handshake13Flight second;
        /** CURRENT + PREVIOUS (r2 may run); {@code false} = the LIVE offer over the KEPT flights (nothing ever seals). */
        private final boolean reconnecting;
        private Handshake13Flight answered;
        private Handshake13Flight.Event event;
        /** The first flight's {@code sealCapped()} before {@link #run} (0 with no first flight; {@link #heldByCap}). */
        private long firstSealCappedBefore;
        /** The answer came from {@code onSiblingChallenge} (r2; {@link #viaSibling}). */
        private boolean sibling;

        private Offer(Handshake13Flight first, Handshake13Flight second, boolean reconnecting) {
            this.first = first;
            this.second = second;
            this.reconnecting = reconnecting;
        }

        /**
         * A {@code COOKIE13} the first flight ignored is never offered to the second. r2,
         * reconnecting only: when it echoes one of PREVIOUS's nonces ({@code siblingChallengeOrNull}: PREVIOUS
         * seals nothing) CURRENT seals against its unit ({@code onSiblingChallenge}: CURRENT's cap and budget; a unit
         * CURRENT already sealed against is {@code IGNORE}) and the answer is CURRENT's {@code SEND} / {@code HELD}.
         *
         * <p>The first flight's {@code sealCapped()} is read before it runs
         * — the HELD-cause delta of {@link #heldByCap} — and an r2 answer marks {@link #viaSibling}.
         */
        void run(byte[] payload, long nowMillis) {
            firstSealCappedBefore = first == null ? 0L : first.sealCapped();
            if (answeredBy(first, payload, nowMillis) || second == null) {
                return;
            }
            if (Handshake13.statusOrNegative(payload) != HandshakeCodec.WELCOME_COOKIE) {
                answeredBy(second, payload, nowMillis);
                return;
            }
            Handshake13.Cookie13 unit = reconnecting && first != null ? second.siblingChallengeOrNull(payload) : null;
            if (unit != null) {
                Handshake13Flight.Event e = first.onSiblingChallenge(unit, nowMillis);
                if (e.kind() != Handshake13Flight.Event.Kind.IGNORE) {
                    answered = first;
                    event = e;
                    sibling = true;
                }
            }
        }

        private boolean answeredBy(Handshake13Flight flight, byte[] payload, long nowMillis) {
            if (flight == null) {
                return false;
            }
            Handshake13Flight.Event e = flight.onWelcome(payload, nowMillis);
            if (e.kind() == Handshake13Flight.Event.Kind.IGNORE) {
                return false;
            }
            answered = flight;
            event = e;
            return true;
        }

        /** The flight whose event is not {@code IGNORE}, or {@code null} (every offered flight ignored it). */
        Handshake13Flight answered() {
            return answered;
        }

        /** {@code IGNORE} when no flight answered. */
        Handshake13Flight.Event.Kind kind() {
            return event == null ? Handshake13Flight.Event.Kind.IGNORE : event.kind();
        }

        Handshake13Flight.Event event() {
            return event;
        }

        /** The answer came from the SECOND flight (PREVIOUS while reconnecting). */
        boolean viaSecond() {
            return answered != null && answered == second;
        }

        /**
         * The answer is CURRENT's seal against a challenge that answered PREVIOUS (r2, {@link #run}); never on the LIVE
         * offer ({@code reconnecting == false}: no unit is ever asked of the second flight).
         */
        boolean viaSibling() {
            return sibling;
        }

        /**
         * The answer is {@code HELD} at the answering flight's SEAL_CAP: its {@code sealCapped()} grew over
         * {@link #run}; a {@code HELD} that is not by cap is the pair budget's refusal ({@code pairDenied()}). Only the
         * FIRST flight can answer {@code HELD} — the second is never offered a {@code COOKIE13}, and r2 seals on the
         * first. Exact because only the READER thread drives a slot flight's challenges (class javadoc, Threads).
         *
         * <p>{@code DoorProbe}'s delta pattern, with
         * {@code Handshake13Flight} untouched.
         */
        boolean heldByCap() {
            return kind() == Handshake13Flight.Event.Kind.HELD && answered == first && first != null
                    && first.sealCapped() > firstSealCappedBefore;
        }

        /** The first-WELCOME row's token: {@code current | current-via-previous | previous | none}. */
        String label() {
            return answered == null ? "none" : answered == first ? (sibling ? "current-via-previous" : "current")
                    : "previous";
        }
    }
}
