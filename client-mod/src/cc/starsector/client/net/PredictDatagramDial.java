package cc.starsector.client.net;

import cc.starsector.client.FallbackAnnounce;
import cc.starsector.client.net.PredictReconcileDatagramSession.AdmissionRejected;
import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.Handshake13;
import cc.starsector.server.runtime.Handshake13Flight;
import cc.starsector.server.runtime.Handshake13PairBudget;
import cc.starsector.server.runtime.HandshakeCodec;
import cc.starsector.server.runtime.HandshakeCrypto;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static cc.starsector.client.net.PredictReconcileDatagramSession.CONNECT_TIMEOUT_MILLIS;
import static cc.starsector.client.net.PredictReconcileDatagramSession.HELLO_RETRY_MILLIS;
import static cc.starsector.client.net.PredictReconcileDatagramSession.RECEIVE_BUFFER_BYTES;
import static cc.starsector.client.net.PredictReconcileDatagramSession.nowMillis;

/**
 * The initial admission DIAL of {@link PredictReconcileDatagramSession} (PRDS): the ONE 11-arg
 * {@code connect} body, the socket factory the PRDS B3 re-create shares, and the admission-time
 * schema compare the PRDS re-admit lanes call. WHY: PRDS stood at 3465 nb over its 3441 WARN
 * tier (2nd unrecorded crossing); this is its one static, pre-session, lock-free
 * PHASE (318 nb, 0 instance members; one birth handoff = ctor + {@code session.} seeds + the two
 * thread starts). Provenance: moved VERBATIM from PRDS;
 * body edits = three {@code private} drops + the
 * javadoc links re-pointed at PRDS members. Nonce law: ONE {@link SecureRandom} per dial, built
 * and first drawn HERE on the dialing thread, feeds the version-13 flight
 * and the session's re-HELLO mints (the PRDS class doc). THREADS: runs on
 * the DIALING thread before the session exists; it takes the not-yet-shared session's {@code lock}
 * once (the KEPT seed) and never {@code sendLock}; the daemons start LAST, after every seed.
 * <p>Version 13 ONLY: every attempt joins
 * through ONE {@link Handshake13Flight} (the match password / seat token never rides readable);
 * {@code handshakeVersion} must be {@link Handshake13#VERSION} (else an IAE before any datagram) and the version-12 dial is
 * deleted — a version-12 reply of any kind is the flight's counted IGNORE, NEVER a fallback.
 * {@link #schemaCompareAtAdmission} also runs under the PRDS reader's {@code lock} (stateless).
 * PUBLIC only so the dial planner (the parent package) can arm the process's one team pick
 * ({@link #offerFirstHelloTeam}); every dial member stays package-private behind PRDS.
 *
 */
public final class PredictDatagramDial {

    private PredictDatagramDial() {}

    /** The kernel SO_RCVBUF asked of every session socket (initial + the B3
     * re-create). The OS default (Windows 64 KiB) holds ~300 ms of a serve's down-traffic, so a
     * reader-thread stall of that size DROPS datagrams with no counter anywhere the mod can
     * read; 4 MiB holds ~20 s. Knob {@code cc.predict.udpRcvBufBytes} ({@code 0} = leave the
     * OS default — the rollback lever); the EFFECTIVE size rides the plugin's OPEN row. */
    private static final int UDP_RECEIVE_BUFFER_DEFAULT_BYTES = 4 << 20;

    /** The SO_RCVBUF to ask for — knob {@code cc.predict.udpRcvBufBytes}
     * (env twin {@code CC_PREDICT_UDP_RCV_BUF_BYTES} so the joiner kit can set it without a
     * bat edit); default {@value #UDP_RECEIVE_BUFFER_DEFAULT_BYTES}; {@code 0} = leave the OS
     * default (the rollback lever); malformed or negative keeps the default with one announce
     * (the N2 idiom of {@code PredictReconcileDatagramSession#resolveSilenceDeadlineMillis}, private). */
    private static int resolveReceiveBufferBytes() {
        long v = cc.starsector.client.KnobParse.longOr("cc.predict.udpRcvBufBytes",
                "CC_PREDICT_UDP_RCV_BUF_BYTES", UDP_RECEIVE_BUFFER_DEFAULT_BYTES,
                "udp-rcvbuf-knob-bad");
        if (v < 0 || v > Integer.MAX_VALUE) {
            FallbackAnnounce.announceOnce("udp-rcvbuf-knob-bad#range",
                    "cc.predict.udpRcvBufBytes=" + v + " out of range -> keeping "
                            + UDP_RECEIVE_BUFFER_DEFAULT_BYTES);
            return UDP_RECEIVE_BUFFER_DEFAULT_BYTES;
        }
        return (int) v;
    }

    /** The ONE socket factory for the session's lifetime (the initial dial + the B3 re-create
     * twin): an unbound-to-remote ephemeral {@link DatagramSocket} with the resolved SO_RCVBUF
     * applied. A refused/clamped set is announced, never fatal — the socket still works on the
     * OS default (and the OPEN row shows the truth via {@link PredictReconcileDatagramSession#udpReceiveBufferBytes}). */
    static DatagramSocket newDatagramSocket() throws SocketException {
        DatagramSocket s = new DatagramSocket();
        int want = resolveReceiveBufferBytes();
        if (want <= 0) {
            return s; // knob 0: the OS default, untouched
        }
        try {
            s.setReceiveBufferSize(want);
            int got = s.getReceiveBufferSize();
            if (got < want) {
                FallbackAnnounce.announceOnce("udp-rcvbuf-clamped",
                        "SO_RCVBUF requested " + want + " -> effective " + got
                                + " (OS cap; Linux: net.core.rmem_max)");
            }
        } catch (SocketException | IllegalArgumentException setFailed) {
            FallbackAnnounce.announceOnce("udp-rcvbuf-set-failed",
                    "SO_RCVBUF " + want + " refused: " + setFailed.getMessage()
                            + " -> OS default stays");
        }
        return s;
    }

    /** The kernel's answer for {@code s}, or -1 when the socket will not say (closed). */
    static int effectiveReceiveBufferBytes(DatagramSocket s) {
        try {
            return s.getReceiveBufferSize();
        } catch (SocketException | RuntimeException unreadable) {
            return -1;
        }
    }

    /**
     * The process's ONE team pick (the join window's Team choice: 0 = the server picks, 1 = South, 2 = North) —
     * the ONE-SHOT law: a process sends its pick on its first ADMITTED dial only. Every pilot dial reads it; the
     * dial a door ADMITS clears it, so every later dial of the process (each in-game click, a reconnect) sends 0 and
     * the server's own memory of this player's team answers. An un-admitted dial (no answer, a refusal, a cancel)
     * leaves it set: the pick has not reached a door that kept it. Offered at most once per process
     * ({@link #offerFirstHelloTeam}); a re-offer is ignored, so a second plan read can never re-arm a sent pick.
     *
     */
    private static final AtomicInteger FIRST_HELLO_TEAM = new AtomicInteger(0);
    private static final AtomicBoolean FIRST_HELLO_TEAM_OFFERED = new AtomicBoolean(false);

    /**
     * Arms the process's one team pick ({@code team} 1 or 2; 0 = nothing to send). The FIRST call of the process wins;
     * every later call is ignored (returns false). Outside 0..2 = an IAE before anything is armed (the caller's read
     * already clamps an unlawful property to 0).
     *
     */
    public static boolean offerFirstHelloTeam(int team) {
        if (team < 0 || team > Handshake13.TEAM_MAX) {
            throw new IllegalArgumentException("team must be 0.." + Handshake13.TEAM_MAX + "; got " + team);
        }
        if (!FIRST_HELLO_TEAM_OFFERED.compareAndSet(false, true)) {
            return false;
        }
        FIRST_HELLO_TEAM.set(team);
        return true;
    }

    /** The pick the NEXT pilot dial of this process sends (0 once a door admitted it). */
    public static int pendingFirstHelloTeam() {
        return FIRST_HELLO_TEAM.get();
    }

    /** Set once {@link HandshakeCrypto#warm()} returned on some sender thread (two racing first
     * warms both run it — harmless, it is a throwaway computation). */
    private static volatile boolean hs13Warm;

    /**
     * The X25519 / ChaCha20 first-execution cost (40-90 ms) is paid by the
     * caller's OFF-frame sender thread (the dial, the probe) before its first flight, once per
     * process. Throws the warm's {@code IllegalStateException} (a broken provider) — the caller's
     * attempt fails; it is never a reason to send version 12.
     *
     *
     */
    static void warmHs13Once() {
        if (!hs13Warm) {
            HandshakeCrypto.warm();
            hs13Warm = true;
        }
    }

    /** The ONE {@link Handshake13PairBudget} host key every kit sender
     * passes (the dial, the probe, the re-HELLO pump, VCHK) — the TYPED host, lower-cased
     * ({@code Locale.ROOT}), + ":" + port, so the senders share one bucket per door. WHY the typed
     * locator, never the resolved address: a door that answers the typed name's DNS with a new
     * address per lookup must not earn a fresh bucket per DNS answer (the password
     * locator, "no DNS"). {@code getHostString()} never triggers a reverse lookup: it returns the
     * name the address was built from, or the literal text of a typed IP.
     *
     * */
    static String budgetHostKey(InetSocketAddress server) {
        return Handshake13PairBudget.hostKey(server.getHostString(), server.getPort()); // the ONE key law
    }

    /** (83) CL-1 — the ONE admission-time schema compare (replaces the deleted per-frame
     * value-based {@code n1-awss-below-floor}/{@code n4-cloak-prev10} announces, which
     * false-fired on healthy sessions: AWSS stamps are per-frame VALUE-based). A serve ceiling
     * below the client's known ceiling announces (default) or refuses the session under
     * {@code cc.client.strictSchema} (default OFF). Returns {@code null} when admissible, else
     * the strict-mode refusal reason. */
    static String schemaCompareAtAdmission(int serveSchemaMax, String phase) {
        if (serveSchemaMax >= AwssSnapshotReader.HIGHEST_KNOWN_SCHEMA_VERSION) {
            return null;
        }
        FallbackAnnounce.announceOnce("n1-server-schema-below-client#" + phase,
                "server serveSchemaMax v" + serveSchemaMax + " < client known v"
                        + AwssSnapshotReader.HIGHEST_KNOWN_SCHEMA_VERSION
                        + " -> upper tiers absent whole-session (CR/dict/missile-pose)");
        if (cc.starsector.client.KnobParse.boolOr("cc.client.strictSchema", false,
                "strict-schema-knob-bad")) {
            return "server serveSchemaMax v" + serveSchemaMax + " below client known v"
                    + AwssSnapshotReader.HIGHEST_KNOWN_SCHEMA_VERSION
                    + " (cc.client.strictSchema=true refuses degraded serve)";
        }
        return null;
    }

    /** The ONE connect body — PRDS's {@code connect} overloads delegate here (the overloads stayed
     * in PRDS at the carve). {@code variantId} rides
     * the HELLO v8 tail verbatim (bounded by the caller: ≤64 single-line printable, the
     * codec's decode law; the server canonicalizes against its own allowlist). {@code observer}
     * = the posture (see the 9-arg observer overload); {@code helloTimeoutMillis} =
     * this attempt's HELLO horizon (≤ 0 keeps {@value PredictReconcileDatagramSession#CONNECT_TIMEOUT_MILLIS}).
     * {@code lane} = the credential field {@code seatToken} came from (non-null at both versions;
     * the version-13 envelope's PASSWORD / TOKEN slot); {@code handshakeVersion} = 13 (the
     * class doc) — anything else, or a null lane, is an IAE before any datagram. {@code seatToken}:
     * null = an IAE; blank = an IAE for a PILOT only — an OBSERVER may dial blank (the kit's reel
     * door is observer-only and OPEN; a real door answers a blank observer
     * with a terminal credential refusal, {@code match_password_required} /
     * {@code observer_password_mode_only}). */
    static PredictReconcileDatagramSession connect(String host, int port, long shipId,
            int selectedWeaponGroup, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint, String variantId, int readDeadlineMillis,
            boolean observer, int helloTimeoutMillis) throws IOException {
        final int helloHorizon = helloTimeoutMillis > 0 ? helloTimeoutMillis : CONNECT_TIMEOUT_MILLIS;
        if (host == null) {
            throw new IllegalArgumentException("host must not be null");
        }
        if (port < 0) {
            throw new IllegalArgumentException("port must be >= 0; got " + port);
        }
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (observer && shipId != 0L) {
            // An observer holds no seat — a concrete id here is a caller bug (the
            // plugin forces 0 under ObserverMode.ON); fail BEFORE any datagram, like L1.
            throw new IllegalArgumentException("observer admission requires shipId 0 (seat 0 /"
                    + " ship 0 — an observer holds no seat); got " + shipId);
        }
        if (selectedWeaponGroup < 0) {
            throw new IllegalArgumentException("selectedWeaponGroup must be >= 0; got " + selectedWeaponGroup);
        }
        if (lane == null) {
            throw new IllegalArgumentException("lane must not be null");
        }
        if (handshakeVersion != Handshake13.VERSION) {
            // THE FLIP - the kit speaks ONLY version 13; the parameter stays (its drop is parked)
            throw new IllegalArgumentException("handshakeVersion must be " + Handshake13.VERSION
                    + "; got " + handshakeVersion);
        }
        if (seatToken == null || (!observer && seatToken.trim().isEmpty())) {
            // A PILOT's blank: the server would answer seat_token_required; fail EARLIER and louder
            // — the udp pilot lane always has a descriptor-minted token (the CcClientModPlugin
            // composition gate). An OBSERVER may dial blank (see the method
            // javadoc).
            throw new IllegalArgumentException("seatToken is required for the udp transport"
                    + " (descriptor seat row; fail-closed — no perimeter-trust door from the mod)");
        }
        InetSocketAddress server = new InetSocketAddress(host, port);
        if (server.isUnresolved()) {
            throw new IOException("predict datagram server address unresolved: " + host);
        }
        DatagramSocket socket = newDatagramSocket(); // Sized SO_RCVBUF
        Handshake13Flight flight = null; // null = never built (the warm / the ctor threw); the catch abandons it
        try {
            // One SecureRandom per dial, constructed and FIRST drawn on this dialing thread (the
            // class doc): the session's later draws under its lock are warm calls.
            SecureRandom rng = new SecureRandom();
            String variant = variantId == null ? "" : variantId;
            final String helloClientId = clientId == null ? "" : clientId;
            final String helloFingerprint = fingerprint == null ? "" : fingerprint;
            // ONE flight per attempt,
            // warmed on this thread first (item 56; a warm failure fails THIS attempt).
            warmHs13Once();
            // the process's one team pick rides this dial only until a door admits one (an observer never picks)
            final int team = observer ? 0 : FIRST_HELLO_TEAM.get();
            flight = new Handshake13Flight(lane, Handshake13.secretBytes(seatToken),
                    observer ? Handshake13.FLAG_OBSERVER : 0, helloFingerprint, helloClientId,
                    shipId, variant, team, Handshake13PairBudget.process().host(budgetHostKey(server)), rng);
            byte[] hello = flight.start(nowMillis()); // null = send nothing (b2 contract)
            int cookieChallenges = 0;
            long deadline = nowMillis() + helloHorizon;
            socket.setSoTimeout(HELLO_RETRY_MILLIS);
            if (hello != null) {
                socket.send(new DatagramPacket(hello, hello.length, server));
            }
            // The retransmit runs on a WALL CLOCK —
            // before, it fired only from the receive timeout, so a server that admitted and then
            // streamed SNAP/CTRL while the WELCOME was lost reset the 250 ms wait on every frame
            // and no HELLO went out until the horizon died. Same 250 ms cadence (no flood);
            // interrupt latency stays <= 250 ms. The flight's byte-identical F2 resend is
            // idempotent at the door (PidsHello13: a duplicate F2 earns the stored WELCOME13).
            long nextHelloAtMillis = nowMillis() + HELLO_RETRY_MILLIS;
            int helloRetransmits = 0;
            int preAdmitDrops = 0;
            int held = 0; // version 13: the flight's HELD answers (never a pre-admit drop)
            boolean underTrafficAnnounced = false;
            byte[] buf = new byte[RECEIVE_BUFFER_BYTES];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (nowMillis() < deadline) {
                long now = nowMillis();
                if (now >= nextHelloAtMillis) {
                    // W13 leg 1b: a cancelled dial (the caller interrupted its thread — a
                    // re-click on a MOVED endpoint) stops HELLOing the old door here, at the
                    // next retransmit tick; the flag stays set for the caller's own check.
                    if (Thread.currentThread().isInterrupted()) {
                        // plain IOException on purpose: InterruptedIOException is a loader-B
                        // DENIED name (check-b-deny-refs) — the caller reads the interrupt flag.
                        throw new IOException("predict datagram HELLO cancelled (dial interrupted"
                                + " after " + helloRetransmits + " retransmits)");
                    }
                    // The flight's byte-identical latest F2 / F1; null = send nothing.
                    byte[] resend = flight.retransmitOrNull();
                    if (resend != null) {
                        helloRetransmits++;
                        socket.send(new DatagramPacket(resend, resend.length, server));
                    }
                    nextHelloAtMillis = now + HELLO_RETRY_MILLIS;
                    if (preAdmitDrops > 0 && !underTrafficAnnounced) {
                        // Names the lost-WELCOME-under-traffic class once per dial.
                        underTrafficAnnounced = true;
                        FallbackAnnounce.announceOnce("hello-retransmit-under-traffic#initial",
                                "retransmits=" + helloRetransmits + ", pre_admit_drops="
                                        + preAdmitDrops);
                    }
                }
                socket.setSoTimeout((int) Math.max(1L,
                        Math.min(HELLO_RETRY_MILLIS, nextHelloAtMillis - now)));
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException retry) {
                    continue; // pure flow: the wall clock at the loop top owns the retransmit
                }
                DatagramFrameCodec.Frame frame =
                        DatagramFrameCodec.decode(packet.getData(), packet.getLength());
                if (frame == null || frame.channel() != DatagramFrameCodec.WireChannel.WELCOME
                        || !packet.getSocketAddress().equals(server)) {
                    preAdmitDrops++;
                    continue; // spoofable pre-admission input: silent-drop posture
                }
                // The flight reads the from-server
                // WELCOME-channel payload; a version-12 COOKIE / REJECT / ADMITTED is the flight's IGNORE (counted
                // below), never a fallback and never a send (the version-12 arm is deleted).
                long sealCappedBefore = flight.sealCapped(); // the HELD-cause delta (DoorProbe's pattern)
                Handshake13Flight.Event event = flight.onWelcome(frame.payload(), nowMillis());
                if (event.kind() == Handshake13Flight.Event.Kind.SEND) {
                    byte[] seal = event.datagram(); // the F2 sealed against a fresh challenge
                    cookieChallenges++;
                    socket.send(new DatagramPacket(seal, seal.length, server));
                    nextHelloAtMillis = nowMillis() + HELLO_RETRY_MILLIS;
                    continue;
                }
                if (event.kind() == Handshake13Flight.Event.Kind.REJECTED) {
                    String reason = event.rejected().reason().isEmpty() ? "(no reason)"
                            : event.rejected().reason();
                    FallbackAnnounce.announceOnce("welcome-rejected#initial", "reason=" + reason);
                    throw new AdmissionRejected("admission", reason);
                }
                if (event.kind() == Handshake13Flight.Event.Kind.HELD) {
                    // A HELD is the flight's own refusal (its SEAL_CAP
                    // or the pair budget), never the lost-WELCOME case hello-retransmit-under-traffic names; the
                    // first one says which (sealCapped() grew = the cap, else the budget); the count rides into
                    // the session (connectHeld, the ended line's connect_held=)
                    held++;
                    if (held == 1) {
                        FallbackAnnounce.announceOnce("hello-held#initial", "cause="
                                + (flight.sealCapped() > sealCappedBefore ? "seal_cap" : "pair_budget"));
                    }
                    continue;
                }
                if (event.kind() != Handshake13Flight.Event.Kind.ADMITTED) {
                    preAdmitDrops++; // IGNORE (not ours, forged, duplicate, any version-12 reply)
                    continue;
                }
                // the door admitted the pick this flight carried: every later dial of this process sends 0
                if (team != 0) {
                    FIRST_HELLO_TEAM.compareAndSet(team, 0);
                }
                // the kind is never read; the auth share + root ride the whole
                // Admitted13 into the session's /auth context.
                return admit(event.admitted().welcome(), shipId, selectedWeaponGroup, socket,
                        server, clientId, seatToken, lane, handshakeVersion, rng, fingerprint,
                        variant, readDeadlineMillis, observer, helloRetransmits, preAdmitDrops,
                        cookieChallenges, held, flight, event.admitted());
            }
            throw new IOException("predict datagram HELLO timed out after " + helloHorizon
                    + " ms (" + helloRetransmits + " retransmits, no nonce-matching WELCOME)");
        } catch (Throwable t) {
            if (flight != null) {
                flight.abandon(); // a failed dial ends its own flight here
            }
            socket.close();
            throw t;
        }
    }

    /**
     * The ONE admit tail, reached with {@code Admitted13.welcome()} of the opened WELCOME13:
     * the fail-closed checks, the global seeds, the session birth and its hand-off seeds, then
     * the two daemons start LAST. {@code held} = the flight's HELD answers;
     * {@code flight} = the hand-off, the session's first kept flight (non-null);
     * {@code admitted13} = the opened WELCOME13 whose auth share + root seed the session's
     * {@code /auth} context. The version-12 hand-off
     * ({@code heldCookie}) is deleted with its arm.
     * Moved out of {@link #connect} — body edits = the ctor's three new
     * arguments, the {@code dialFlight} seed and the auth seed; the dialing thread (the auth seed
     * takes the not-yet-shared session's lock).
     *
     *
     */
    private static PredictReconcileDatagramSession admit(HandshakeCodec.WelcomePayload welcome,
            long shipId, int selectedWeaponGroup, DatagramSocket socket, InetSocketAddress server,
            String clientId, String seatToken, Handshake13Flight.Lane lane, int handshakeVersion,
            SecureRandom rng, String fingerprint, String variant, int readDeadlineMillis,
            boolean observer, int helloRetransmits, int preAdmitDrops, int cookieChallenges, int held,
            Handshake13Flight flight, Handshake13.Admitted13 admitted13)
            throws IOException {
        if (welcome.sessionId() == 0L) {
            throw new IOException("predict datagram WELCOME admitted with session id 0"
                    + " (protocol break — 0 is the no-session sentinel)");
        }
        String schemaRefusal = schemaCompareAtAdmission(welcome.serveSchemaMax(), "initial");
        if (schemaRefusal != null) {
            throw new IOException("predict datagram admission refused by client: "
                    + schemaRefusal);
        }
        // The observer bit must AGREE with what we asked — fail-closed
        // both ways, BEFORE any global seed below. Requested + absent = an older server
        // (or a pilot admit we never asked for) — a TERMINAL typed refusal, so the
        // connect loop stops instead of re-HELLOing through the whole attempt budget; unrequested + present =
        // a protocol break (the door never volunteers seat 0). AdmissionRejected (not a
        // plain IOException) because the plain class is the NON-terminal retry shape.
        boolean welcomeObserver = (welcome.matchFlags()
                & HandshakeCodec.WELCOME_FLAG_SEAT_OBSERVER) != 0;
        if (observer != welcomeObserver) {
            String reason = observer ? "observer_not_granted" : "observer_unrequested";
            FallbackAnnounce.announceOnce("welcome-observer-mismatch#initial",
                    "reason=" + reason + " requested=" + observer + " matchFlags=0x"
                            + Integer.toHexString(welcome.matchFlags())
                            + " boundShipId=" + welcome.boundShipId());
            throw new AdmissionRejected("admission", reason);
        }
        if (observer && welcome.boundShipId() != 0L) {
            // The door binds seat 0 / ship 0 to an observer by construction — a concrete
            // id here would make the mission builder's NO-own-hull build and the
            // session's operative id disagree (the PRCP init guard would then refuse).
            FallbackAnnounce.announceOnce("welcome-observer-mismatch#bound",
                    "reason=observer_bound_nonzero boundShipId=" + welcome.boundShipId());
            throw new AdmissionRejected("admission", "observer_bound_nonzero");
        }
        // A NON-AUTO pilot must be bound to the
        // ship it asked for. The token door binds the TOKEN's seat and never reads the requested
        // id, so a hand-set cc.predict.shipId on another seat's token would fly a session the door
        // pins to the bound ship (silently unplayable). The password door reaches this gate too: a
        // hand-set cid that still HOLDS another seat is REJOINED onto it (that held claim clears
        // once the old session dies). Refused BEFORE every global seed below; a
        // bound <= 0 under a concrete request is a protocol break and refuses too. TERMINAL
        // (PredictFailBannerCombatPlugin.isTerminalRefusal): the same knobs mismatch again.
        if (shipId != 0L && !observer && welcome.boundShipId() != shipId) {
            FallbackAnnounce.announceOnce("welcome-bound-mismatch#initial", "reason=bound_mismatch"
                    + " requested=" + shipId + " boundShipId=" + welcome.boundShipId());
            throw new AdmissionRejected("admission", "bound_mismatch");
        }
        socket.setSoTimeout(0); // the reader daemon blocks from here on
        // Adopt the server's per-match rate BEFORE any law can call
        // ticksPerSecond() — the v3 WELCOME is the ONE rate source (kit line retired).
        cc.starsector.client.ServerTickRate.seedFromWire(welcome.tickRate());
        cc.starsector.client.ClientWireKnobs.resetDeliveredCadenceAtAdmit(); // Raw until this admit's PSTB
        // Adopt the server-owned match config BEFORE the mission builder
        // can read it — the v5 WELCOME is the ONE source (the kit -D twin is retired).
        cc.starsector.client.MatchConfigFromWire.seedFromWire(welcome.matchFlags());
        // the server-owned custom-variant policy (v11 tail) — the SPA
        // editor's ping reads it; seeded beside its siblings so it is live before the
        // editor can answer a "connected" ping.
        cc.starsector.client.CustomConfigFromWire.seedFromWire(welcome.customTier(),
                welcome.customPerSeat(), welcome.customSModCap(), welcome.customFlags());
        // Publish the server-assigned
        // seat for the mission builder's AUTO resolve — beside its two seed precedents.
        cc.starsector.client.AssignedSeatFromWire.seedFromWire(welcome.boundShipId());
        // AUTO seat — requested shipId 0 means "server assigns"
        // (password lane only; the CcClientModPlugin composition gate). Adopt the
        // WELCOME's boundShipId as the session's operative id: the field is final, so
        // every re-HELLO resends the CONCRETE id (the ledger's cid rejoin returns this
        // same seat regardless). An AUTO admit without a bound seat is a protocol
        // break — the password door always binds a seat — treat as reject, fail-closed.
        // An OBSERVER keeps 0 — seat 0 / ship 0 IS its operative id (the
        // gate above already pinned boundShipId 0), so the AUTO adopt below is pilots-only.
        long resolvedShipId = shipId;
        if (shipId == 0L && !observer) {
            if (welcome.boundShipId() <= 0L) {
                throw new IOException("predict datagram AUTO-seat admit carried"
                        + " boundShipId=" + welcome.boundShipId()
                        + " (no assigned seat - fail-closed reject)");
            }
            resolvedShipId = welcome.boundShipId();
        }
        PredictReconcileDatagramSession session = new PredictReconcileDatagramSession(
                resolvedShipId, selectedWeaponGroup, socket, server, welcome.sessionId(),
                welcome.startingRoundId(), clientId, seatToken, lane, handshakeVersion, rng,
                fingerprint, variant, readDeadlineMillis, observer);
        session.serverBoundShipId = welcome.boundShipId();
        session.serveSchemaMax = welcome.serveSchemaMax();
        session.serverLife = welcome.serverLife();
        // The initial dial's meters.
        session.connectHelloRetransmits = helloRetransmits;
        session.connectPreAdmitDrops = preAdmitDrops;
        session.connectCookieChallenges = cookieChallenges; // the dial's cookie-challenge count
        session.connectHeld = held; // the flight's HELD answers
        // The dial's flight (it carries the held unit — the
        // B4 pump's seed: same socket, same source port, so an episode's first re-HELLO skips
        // its challenge RTT) is the session's first kept flight; the timer thread's
        // exit abandons it. The field is set before the daemons start, so no lock.
        session.dialFlight = flight;
        session.seedAuth13(admitted13); // Before the daemons
        // The reference-only KEPT seed BEFORE the
        // reader can run — a WELCOME13 for a newer dial seal already on the socket must meet
        // the dial's flight in KEPT, never an empty slot (a counted duplicate that leaves the
        // kit on the displaced session). No flight call; the timer's first pass only fills
        // the held unit (the seed is once).
        synchronized (session.lock) {
            session.hs13.seedLocked(flight, null);
        }
        session.voice.onWelcomeFlags(welcome.matchFlags()); // W12 L3 voice bit (0x04)
        session.reader.start();
        session.timer.start();
        return session;
    }
}
