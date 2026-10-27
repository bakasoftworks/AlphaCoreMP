package cc.starsector.client.net;

import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.DatagramReliability;
import cc.starsector.server.runtime.DoorRejectReasons;
import cc.starsector.server.runtime.DoorShares13;
import cc.starsector.server.runtime.Handshake13;
import cc.starsector.server.runtime.HandshakeCodec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

/**
 * wq P18 — the IN-PROCESS reel feed for the OBSERVER window: a fake loopback UDP door
 * that re-speaks one serve reel's DOWN records into the REAL {@link PredictReconcileDatagramSession}
 * so the observer renders a past flight with no server running. Nothing on the client's receive
 * path is bypassed: the session HELLOs this door like any server, gets an admitted observer WELCOME,
 * and its reader thread / reliability core / freshness buffer / merger / renderer run unchanged —
 * the door is the server's protocol peer, built from the SAME folded classes the server uses
 * ({@link DatagramReliability} is symmetric; {@link HandshakeCodec} and {@link DatagramFrameCodec}
 * are the wire). Knob = {@code cc.observer.reel} (resolved by {@code ObserverMode}); the plugin
 * points its predict endpoint at {@link #port()} ({@code ClientLaunchKnobs.resolvePredictEndpoint}).
 *
 * <p><b>Fidelity law (what the feed is and is not).</b> The reel body is the tick's RAW frame set
 * ({@code DownTickComposition.composedDownRecord}: {@code [GPEE*][EDSP*][AWSS chunk*]}) plus each
 * fan record's one ctrl frame; the live peers received the same bytes CAWZ-wrapped and, with
 * {@code snapDelta} on, AWSD deltas in place of raw AWSS — the client's SNAP lane accepts raw
 * AWSS natively ({@code decodePayload} passes an unwrapped frame through), so the feed re-speaks
 * AWSS every tick. NOT in a reel and therefore NOT fed: SEAT baselines/events, DIVE starts and
 * VOICE (ctrl-fan-only / voice-channel lanes). A gap the feed reproduces is in the client render
 * path; a gap it does NOT reproduce localizes to the live per-peer lane (delta/keyframe cadence,
 * relevance, timing). Envelope seqs are minted fresh by the door's core (recorded seqs never
 * existed — the reel stores payloads); round ids start at 0 and bump at each re-spoken MRST
 * exactly as the serve door bumps ({@code fanMatchReset}).
 *
 * <p><b>Pacing.</b> Absolute schedule on the reel's own record epoch-millis (the
 * {@code ReplayPlaybackServer} pacer): anchor = the first DOWN record, target =
 * anchor + (millis - first) / speed; {@code speed 0} = unpaced (the freshness buffer conflates).
 * The core's clock is the door's monotonic clock — reel millis only decide WHEN a frame releases.
 *
 * <p><b>Negative control</b> ({@code cc.observer.reel.drop} = {@code <shipId>:<fromTick>-<toTick>}):
 * every AWSS chunk that carries the ship inside the tick window is withheld, so the observer keeps
 * rendering a plausible scene (the hull frozen / extrapolated) while the reel's wire ring (the
 * filmstrip reads the reel, not the feed) moves on — the {@code -GapCensus} field must go dark or
 * displaced for that ring. A control that the tooling cannot see is a failed control.
 *
 * <p>Single-session by design (one observer window per reel door); a probe HELLO is refused with
 * {@code probe_unsupported} (decisive, the token-lane door's own answer — the dial then connects);
 * a non-observer HELLO is refused. Classloader-B clean: {@code java.net}, {@code java.security}
 * ({@link SecureRandom}, {@link MessageDigest}) + the allowlisted {@code java.io} names only (no
 * file classes — the reel rides {@link CcwrDownFeed}).
 *
 * <p><b>Version 13 only</b> (THE FLIP): a HELLO payload whose
 * byte 0 is {@link Handshake13#VERSION} goes to {@link #onHello13}, an OPEN door that never sees a
 * typed secret ({@link OpenDoor13}); every other HELLO (version 12 included) is dropped unanswered
 * and counted ({@code hellosNot13Dropped}). A seal carrying a typed secret is refused
 * {@value #REJECT_NO_SECRET_ONLY} before any open. If the version-13 half cannot be built at door
 * start, {@link #startOrGet} FAILS (an {@link IOException}, the plugin's loud LOCAL VANILLA path) —
 * a door that would serve nothing never starts.
 */
public final class ReelLoopbackDoor {

    /** Door options (resolved by the plugin from the {@code cc.observer.reel*} knobs). */
    public static final class Options {
        public String ccwrPath;
        public double speed = 1.0d;
        /** WELCOME matchFlags; bit4 (observer) is OR'd in unconditionally. Default: observer only. */
        public int matchFlags = HandshakeCodec.WELCOME_FLAG_SEAT_OBSERVER;
        public long dropShipId = -1L;
        public long dropFromTick = Long.MIN_VALUE;
        public long dropToTick = Long.MAX_VALUE;
        /** Sink for the door's log rows (the plugin wires {@code PilotWatchLog.info}). */
        public java.util.function.Consumer<String> log = System.out::println;
    }

    private static final long TIMER_MILLIS = 15L;
    private static final long BYE_RETRY_MILLIS = 250L;
    private static final int BYE_TRIES = 12;
    private static final long CTRL_WINDOW_WAIT_MILLIS = 2_000L;
    private static final long SERVER_LIFE = 0x5245454C_444F4F52L; // "REELDOOR": one boot per door
    /** The REJECTED13 token of a version-13 seal that carries a typed secret. The ONE
     * home of the spelling: a KIT-minted token, so it stays out of the server's {@link DoorRejectReasons}
     * registry (as {@code reel_observer_only} does). TERMINAL on the client
     * ({@code PredictFailBannerCombatPlugin.isTerminalRefusal} switches on this literal; pinned through this
     * constant by {@code ChatInputLawSelftest} X1) — public for that row and the selftest fake door. */
    public static final String REJECT_NO_SECRET_ONLY = "reel_no_secret_only";
    /** Cookie bytes the door mints once at start (1..32 B law of {@code Handshake13.encodeCookie}). */
    private static final int HS13_COOKIE_BYTES = 16;

    private static ReelLoopbackDoor instance;

    private final Options opt;
    private final DatagramSocket socket;
    private final int port;
    private final CcwrDownFeed feed;
    private final int tickRate;
    private final Object sendLock = new Object();
    private final Random random = new Random();
    /** The version-13 OPEN half, built once at door start; never {@code null} (a failed build fails {@link #startOrGet}). */
    private final OpenDoor13 hs13;
    private volatile boolean closed;
    private volatile SocketAddress client;
    private volatile long sessionId;
    private volatile long welcomeNonce;
    private volatile byte[] welcomeDatagram;
    private volatile int roundId;
    private volatile DatagramReliability core;
    private volatile boolean byeSeen;
    private volatile boolean feedDone;
    // Diagnostic counters (never gate behaviour): written by up to three daemon
    // threads (socket reader, pacer, timer) and read on a DIFFERENT thread — the pacer's summary()
    // and reel-END rows, plus the socket's onBye row. Each carries its writer(s). Single-writer +
    // cross-thread read => volatile long (the ++ stays a lone-writer op; volatile only buys the
    // reader a fresh value); multi-writer increment => AtomicLong (the ++ must itself be atomic).
    // java.util.concurrent.atomic is B-legal (no file/reflection/Runtime pulled in).
    // -- socket-thread writers, read cross-thread by the pacer in summary():
    volatile long hellosNot13Dropped;      // socketLoop: HELLOs whose byte 0 is not 13, dropped unanswered (package-private: Hs13OpenDoorRows reads it)
    private volatile long probes;          // onHello13
    private volatile long refused;         // onHello13
    private volatile long clientSnapIn;    // snapDelivery callback — fires ONLY under onDatagram (socket thread)
    private volatile long clientCtrlIn;    // ctrlDelivery callback — fires ONLY under onDatagram (socket thread)
    private volatile long clientAckIn;     // onData
    private volatile long dropped;         // socketLoop (undecodable frame)
    private volatile long handlerFailures; // socketLoop handler catch
    // -- version-13 OPEN half: socket-thread writers (onHello13 and its
    //    sendCookie13), read cross-thread by the pacer's summary() and by the in-package rows
    //    (Hs13OpenDoorRows) — single writer + cross-thread read => volatile long, package-private:
    volatile long hs13Cookies;             // onHello13 via sendCookie13: COOKIE13s sent
    volatile long hs13NoSecretSeals;       // onHello13: parsed F2s carrying FLAG_NO_SECRET
    volatile long hs13SecretRefused;       // onHello13: F2s with a typed secret, refused, NEVER opened
    volatile long hs13OpenAttempts;        // onHello13: DoorShares13.open calls
    volatile long hs13Dropped;             // onHello13: malformed F1/F2, a failed / erased open, a handle mismatch, a request shorter than its COOKIE13
    volatile long hs13Unserved;            // onHello13: version-13 HELLOs dropped, the fixed bucket's pair missing
    // -- pacer-thread writers, read cross-thread by the socket in onBye():
    private volatile long recordsFed;      // pacerLoop
    private volatile long lastTick = -1L;  // pacerLoop
    // -- multi-writer increment: sendRaw runs on socket (onHello13/onBye), pacer (BYE retries) AND
    //    socket/pacer/timer (via the emitFromCore sink) => the increment must be atomic, not a
    //    plain long ++ (the sendLock serialises writers but the summary() read is un-locked):
    private final java.util.concurrent.atomic.AtomicLong sendFailures = new java.util.concurrent.atomic.AtomicLong();
    // -- pacer-thread ONLY (written in speak()/pacerLoop, read in the SAME thread's summary()/reel-END
    //    row): no cross-thread hop, so a plain long is already correct — left unsynchronised on purpose:
    private long framesSnap;               // speak
    private long framesCtrl;               // speak
    private long ctrlRefused;              // speak
    private long controlDropped;           // speak
    private long lagMaxMillis;             // pacerLoop
    private boolean firstDatagramLogged;   // socketLoop — socket thread only, never read elsewhere
    // The sendFailures counter above is an AtomicLong (multi-thread writers).

    private ReelLoopbackDoor(Options opt, DatagramSocket socket, CcwrDownFeed feed, OpenDoor13 hs13) {
        this.opt = opt;
        this.socket = socket;
        this.port = socket.getLocalPort();
        this.feed = feed;
        this.tickRate = feed.tickRateOr(60);
        this.hs13 = hs13;
    }

    /**
     * The version-13 OPEN door half: the REAL
     * {@link DoorShares13} over an EMPTY {@code prs} with no admin secret and ONE empty mix (the
     * password door's {@code mixes = {EMPTY}} shape — {@code Handshake13.openWrapsOrNull} refuses an
     * empty {@code mixesM}), so the only seal it can open is a {@code NO_SECRET} one sealed on the
     * public generator; {@link #onHello13} refuses a typed secret before any open. Built ONCE at door
     * start on the starting thread, never lazily on the socket thread (the X25519 first execution
     * and a broken provider both land at start). WHY a FIXED bucket, never rolled: the door serves
     * one playback to one loopback observer and holds no secret, so the per-bucket scalar lifetime
     * that bounds a real door's exposure of a typed secret guards nothing here; every lookup asks
     * {@link DoorShares13#pairOrNull} for {@link #BUCKET} (the constructor mints it and nothing
     * erases it). The cookie = {@value #HS13_COOKIE_BYTES} random bytes from the door's own
     * {@link SecureRandom}, compared with {@link MessageDigest#isEqual}: loopback has no
     * return-routability question, the cookie only keeps the flight's two steps.
     *
     *
     */
    private static final class OpenDoor13 {
        static final long BUCKET = 0L;
        final SecureRandom rng;
        final DoorShares13 shares;
        final byte[] cookie;
        final byte[] laneSalt;
        final byte[][] mixes = {new byte[0]};

        private OpenDoor13(SecureRandom rng, DoorShares13 shares, byte[] cookie) {
            this.rng = rng;
            this.shares = shares;
            this.cookie = cookie;
            this.laneSalt = shares.laneSalt();
        }

        /** The half, or a THROW ({@code RuntimeException} / {@code LinkageError}) that {@link #startOrGet}
         * turns into its loud failure (R5 ruled: at the flip the door has no other admit
         * path, so a door that would serve nothing never starts). The warm runs FIRST, as the real door's
         * does ({@code PidsHello13}'s constructor): the shares below exercise only the generator + X25519,
         * so a broken AEAD / HKDF would otherwise build and then throw on every open.
         * It shares the dial's once-per-process flag; the cost lands at door
         * start (application load; a click's endpoint re-resolve only re-tries a failed start). */
        static OpenDoor13 build() {
            PredictDatagramDial.warmHs13Once();
            SecureRandom rng = new SecureRandom();
            DoorShares13 shares = new DoorShares13(new byte[0], null, rng, BUCKET);
            byte[] cookie = new byte[HS13_COOKIE_BYTES];
            rng.nextBytes(cookie);
            return new OpenDoor13(rng, shares, cookie);
        }
    }

    /** Start the process-wide door (idempotent: a second call returns the live one) — opens the
     * reel header, binds an ephemeral loopback port, builds the version-13 half and starts the
     * socket thread. Throws on a bad reel, a bind, or a version-13 half that does not build (that
     * one = an {@link IOException} with fixed text + the throwable's simple class name, its message
     * never printed; the socket and the reel close first) — the plugin fails loud, LOCAL VANILLA. */
    public static synchronized ReelLoopbackDoor startOrGet(Options opt) throws IOException {
        if (instance != null && !instance.closed) {
            return instance;
        }
        CcwrDownFeed feed = CcwrDownFeed.open(opt.ccwrPath);
        DatagramSocket socket;
        try {
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        } catch (IOException | RuntimeException bind) {
            feed.close();
            throw bind;
        }
        OpenDoor13 hs13;
        try {
            hs13 = OpenDoor13.build();
        } catch (RuntimeException | LinkageError broken) {
            // R5 ruled — never start a door that serves nothing
            socket.close();
            feed.close();
            throw new IOException("reel door: the version-13 door half did not build at door start ("
                    + broken.getClass().getSimpleName() + "); the door serves version 13 only, so it does not start");
        }
        ReelLoopbackDoor door = new ReelLoopbackDoor(opt, socket, feed, hs13);
        Thread reader = new Thread(door::socketLoop, "cc-reel-door-socket");
        reader.setDaemon(true);
        reader.start();
        instance = door;
        opt.log.accept("reel door: LISTENING " + socket.getLocalSocketAddress() + " reel=" + opt.ccwrPath
                + " created=" + feed.createdMillis + " tickRate=" + door.tickRate
                + " speed=" + opt.speed + " flags=0x" + Integer.toHexString(opt.matchFlags
                        | HandshakeCodec.WELCOME_FLAG_SEAT_OBSERVER)
                + (opt.dropShipId >= 0L ? " CONTROL drop ship=" + opt.dropShipId + " ticks="
                        + opt.dropFromTick + ".." + opt.dropToTick : "")
                + " hs13=open"
                + " meta descriptor.id=" + feed.meta.getOrDefault("descriptor.id", "(none)")
                + " git.head=" + feed.meta.getOrDefault("git.head", "?"));
        return door;
    }

    public static synchronized ReelLoopbackDoor current() {
        return instance;
    }

    public int port() {
        return port;
    }

    /** Header meta of the reel (e.g. {@code descriptor.id} — the plugin checks it against the
     * loaded descriptor before dialling). */
    public String meta(String key) {
        return feed.meta.get(key);
    }

    public boolean feedDone() {
        return feedDone;
    }

    /** The admitted session's id, {@code 0} before any admit (Hs13OpenDoorRows; any thread). */
    long admittedSessionId() {
        return welcomeDatagram == null ? 0L : sessionId;
    }

    // ------------------------------------------------------------------ socket thread

    private void socketLoop() {
        byte[] buf = new byte[DatagramFrameCodec.MAX_DATAGRAM_BYTES];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                sleepQuiet(50L);
                continue;
            }
            DatagramFrameCodec.Frame frame = DatagramFrameCodec.decode(packet.getData(), packet.getLength());
            SocketAddress from = packet.getSocketAddress();
            if (!firstDatagramLogged) {
                // The first flight died silent (LISTENING, then nothing) — one row per door
                // says whether datagrams reach this socket at all and how the first one decoded.
                firstDatagramLogged = true;
                opt.log.accept("reel door: first datagram from=" + from + " len=" + packet.getLength()
                        + " channel=" + (frame == null ? "UNDECODABLE" : frame.channel().name()));
            }
            if (frame == null) {
                dropped++;
                continue;
            }
            try {
                switch (frame.channel()) {
                    case HELLO -> {
                        byte[] p = frame.payload();
                        if (p != null && p.length > 0 && (p[0] & 0xFF) == Handshake13.VERSION) {
                            onHello13(p, from); // The OPEN version-13 half
                        } else {
                            hellosNot13Dropped++; // Version 13 only; no answer
                        }
                    }
                    case SNAP, CTRL, ACK -> onData(frame, from);
                    case BYE -> onBye(frame, from);
                    default -> { /* VOICE / VCHK / WELCOME: not a door lane */ }
                }
            } catch (RuntimeException | LinkageError handler) {
                // Fail LOUD, never silent: an uncaught throw here would end the daemon thread and
                // leave the door deaf (the client then HELLOs a listening-but-dead port forever).
                // LinkageError is named on purpose: the flight died exactly so — a folded
                // codec class reached an UNFOLDED server class (NoClassDefFoundError) in-game only.
                handlerFailures++;
                opt.log.accept("reel door: handler EXCEPTION channel=" + frame.channel().name()
                        + " from=" + from + " -> " + handler + " (thread stays up)");
            }
        }
    }

    /**
     * One version-13 HELLO (SOCKET THREAD ONLY), in this order — do not
     * reorder: the fixed bucket's pair missing = drop ({@code hs13Unserved}); an F1 OPEN ({@code [10] == 0}) =
     * a COOKIE13 (none for a request shorter than it, {@link #sendCookie13}); an F2 that does not parse = drop; a typed secret ({@code FLAG_NO_SECRET} clear) =
     * REJECTED13 {@value #REJECT_NO_SECRET_ONLY} with NO open; a probe = {@code probe_unsupported};
     * a pilot = {@code reel_observer_only}; a cookie not this door's = a fresh COOKIE13; an admitted
     * door = the cached WELCOME13 for the admitted nonce from the admitted peer, else ignored; then
     * ONE open — erased / no wrap opened / a bad body / a handle that is not the sealed cid's = drop;
     * else {@link #admitObserver} with a WELCOME13 sealed to the root the open proved. No log row
     * carries a nonce, a secret or more than 8 chars of the cid.
     *
     *
     */
    private void onHello13(byte[] payload, SocketAddress from) {
        OpenDoor13 door13 = hs13;
        DoorShares13.Pair pair = door13.shares.pairOrNull(OpenDoor13.BUCKET);
        if (pair == null) {
            hs13Unserved++; // defensive: a pair is never erased (the bucket is never rolled)
            return;
        }
        if (payload.length >= 11 && payload[10] == 0) {
            Handshake13.Open open = Handshake13.decodeOpenOrNull(payload);
            if (open == null) {
                hs13Dropped++;
                return;
            }
            sendCookie13(open.nonce(), door13, pair, from, payload.length);
            return;
        }
        Handshake13.Seal13 seal = Handshake13.parseSealOrNull(payload);
        if (seal == null) {
            hs13Dropped++;
            return;
        }
        if ((seal.flags() & Handshake13.FLAG_NO_SECRET) == 0) {
            hs13SecretRefused++;
            refuse13(seal.nonce(), REJECT_NO_SECRET_ONLY, from);
            opt.log.accept("reel door: REFUSED a version-13 seal carrying a typed secret ("
                    + REJECT_NO_SECRET_ONLY + ", never opened) from=" + from);
            return;
        }
        hs13NoSecretSeals++;
        if ((seal.flags() & Handshake13.FLAG_PROBE) != 0) {
            probes++;
            refuse13(seal.nonce(), DoorRejectReasons.PROBE_UNSUPPORTED, from);
            return;
        }
        if ((seal.flags() & Handshake13.FLAG_OBSERVER) == 0) {
            refused++;
            refuse13(seal.nonce(), "reel_observer_only", from);
            opt.log.accept("reel door: REFUSED a version-13 pilot seal (reel_observer_only) from=" + from);
            return;
        }
        if (!MessageDigest.isEqual(seal.cookie(), door13.cookie)) {
            sendCookie13(seal.nonce(), door13, pair, from, payload.length);
            return;
        }
        if (welcomeDatagram != null) {
            if (seal.nonce() == welcomeNonce && from.equals(client)) {
                sendRaw(welcomeDatagram, from); // the flight's byte-identical F2 resend
            } else {
                refused++;
                opt.log.accept("reel door: version-13 seal ignored (single-session door) from=" + from);
            }
            return;
        }
        hs13OpenAttempts++;
        DoorShares13.OpenResult result = door13.shares.open(pair, seal, door13.mixes);
        Handshake13.Opened opened = result.openedOrNull();
        if (result.pairErased() || opened == null || !opened.bodyOk()
                || !MessageDigest.isEqual(Handshake13.laneHandle(door13.laneSalt, opened.cid()), seal.handle())) {
            hs13Dropped++;
            return;
        }
        byte[] doorSalt = new byte[Handshake13.SALT_BYTES]; // 16 FRESH bytes per seal (encodeAdmitted's law)
        door13.rng.nextBytes(doorSalt);
        long minted = mintSessionId();
        admitObserver(seal.nonce(), from, minted, Handshake13.encodeAdmitted(seal.nonce(), doorSalt, opened.root(),
                minted, roundId, 0L, tickRate, welcomeFlags(), SERVER_LIFE, HandshakeCodec.CUSTOM_TIER_STANDARD, 4,
                HandshakeCodec.CUSTOM_S_MOD_CAP_ENGINE, 0, door13.shares.newAuthShare().share()),
                " v13-open", printable(opened.cid(), 8));
    }

    /** A COOKIE13 for {@code nonce} carrying the door's cookie, the fixed bucket's shares and the lane salt —
     * or NOTHING (counted {@code hs13Dropped}) when it would be larger than the datagram that asked for it:
     * the real door's 1:1 send-site floor ({@code PidsHello13.answerCookie}, {@code cookie_undersized}).
     * {@code Handshake13.decodeOpenOrNull} accepts an F1 from 11 B; the kit's own F1 is padded to 123 B, so a
     * lawful flight always clears it (pin = row B9). */
    private void sendCookie13(long nonce, OpenDoor13 door13, DoorShares13.Pair pair, SocketAddress to,
            int requestPayloadBytes) {
        byte[] reply = Handshake13.encodeCookie(nonce, door13.cookie, pair.ybM(), pair.ybA(), door13.laneSalt);
        if (reply.length > DatagramFrameCodec.FRAMING_BYTES + requestPayloadBytes) {
            hs13Dropped++;
            return;
        }
        hs13Cookies++;
        sendRaw(reply, to);
    }

    /** A version-13 REJECTED (today's plaintext grammar at byte 13) with the door's fixed tail values. */
    private void refuse13(long nonce, String reason, SocketAddress to) {
        sendRaw(HandshakeCodec.WelcomePayload.encodeRejected(nonce, reason, roundId, tickRate, welcomeFlags(),
                SERVER_LIFE, HandshakeCodec.CUSTOM_TIER_STANDARD, 4, HandshakeCodec.CUSTOM_S_MOD_CAP_ENGINE, 0), to);
    }

    private long mintSessionId() {
        long minted;
        do {
            minted = random.nextLong();
        } while (minted == 0L);
        return minted;
    }

    /**
     * The admit tail ({@link #onHello13} is its one caller since THE FLIP):
     * publish the session, the welcome, the reliability core, send the welcome, one
     * ADMITTED row, then the pacer + timer. {@code welcome} = the COMPLETE wire datagram (the sealed
     * WELCOME13); {@code lane} = the row's lane token ({@code " v13-open"}); {@code cidShown} = the
     * already-bounded cid. SOCKET THREAD ONLY.
     *
     *
     */
    private void admitObserver(long nonce, SocketAddress from, long minted, byte[] welcome, String lane,
            String cidShown) {
        sessionId = minted;
        welcomeNonce = nonce;
        client = from;
        welcomeDatagram = welcome;
        core = new DatagramReliability(DatagramReliability.Config.defaults(), this::emitFromCore,
                (seq, payload) -> clientCtrlIn++, (seq, payload) -> clientSnapIn++);
        sendRaw(welcomeDatagram, from);
        opt.log.accept("reel door: ADMITTED observer" + lane + " client=" + from + " session="
                + Long.toHexString(minted) + " cid=" + cidShown + " round=" + roundId
                + " -> pacer + timer start");
        Thread pacer = new Thread(this::pacerLoop, "cc-reel-door-pacer");
        pacer.setDaemon(true);
        pacer.start();
        Thread timer = new Thread(this::timerLoop, "cc-reel-door-timer");
        timer.setDaemon(true);
        timer.start();
    }

    private void onData(DatagramFrameCodec.Frame frame, SocketAddress from) {
        DatagramReliability c = core;
        if (c == null || !from.equals(client) || frame.sessionId() != sessionId) {
            return;
        }
        DatagramReliability.Channel channel = switch (frame.channel()) {
            case SNAP -> DatagramReliability.Channel.SNAP;
            case CTRL -> DatagramReliability.Channel.CTRL;
            default -> DatagramReliability.Channel.ACK;
        };
        if (channel == DatagramReliability.Channel.ACK) {
            clientAckIn++;
        }
        byte[] payload = frame.payload();
        c.onDatagram(channel, frame.seq(), frame.ctrlAck(), frame.ctrlAckBits(), frame.snapHiSeen(),
                nowMillis(), payload == null || payload.length == 0 ? null : payload);
    }

    private void onBye(DatagramFrameCodec.Frame frame, SocketAddress from) {
        if (!from.equals(client) || frame.sessionId() != sessionId) {
            return;
        }
        boolean first = !byeSeen;
        byeSeen = true;
        sendRaw(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.BYE, roundId, sessionId,
                0L, 0L, 0, 0L, null), from); // BYE-ACK = BYE echo
        if (first) {
            opt.log.accept("reel door: client BYE (echoed) fed=" + recordsFed + " lastTick=" + lastTick);
        }
    }

    // ------------------------------------------------------------------ pacer thread

    private void pacerLoop() {
        long firstMillis = -1L;
        long anchorNanos = 0L;
        try {
            CcwrDownFeed.DownRecord r;
            while (!closed && !byeSeen && (r = feed.next()) != null) {
                if (firstMillis < 0L) {
                    firstMillis = r.millis();
                    anchorNanos = System.nanoTime();
                } else if (opt.speed > 0d) {
                    long targetNanos = anchorNanos
                            + (long) ((r.millis() - firstMillis) / opt.speed * 1_000_000d);
                    long sleepMillis = (targetNanos - System.nanoTime()) / 1_000_000L;
                    if (sleepMillis > 0L) {
                        Thread.sleep(sleepMillis);
                    } else if (-sleepMillis > lagMaxMillis) {
                        lagMaxMillis = -sleepMillis;
                    }
                }
                if (r.tick() >= 0L) {
                    lastTick = r.tick();
                }
                speak(r);
                recordsFed++;
            }
            feedDone = true;
            opt.log.accept("reel door: reel END fed=" + recordsFed + " snap=" + framesSnap + " ctrl=" + framesCtrl
                    + " controlDropped=" + controlDropped + " ctrlRefused=" + ctrlRefused + " lastTick=" + lastTick
                    + " lagMaxMs=" + lagMaxMillis + " truncatedTail=" + feed.truncatedTail
                    + " upSkipped=" + feed.recordsUp + " byeSeen=" + byeSeen);
            for (int i = 0; i < BYE_TRIES && !byeSeen && !closed; i++) {
                sendRaw(DatagramFrameCodec.encode(DatagramFrameCodec.WireChannel.BYE, roundId, sessionId,
                        0L, 0L, 0, 0L, null), client);
                sleepQuiet(BYE_RETRY_MILLIS);
            }
        } catch (IOException | RuntimeException e) {
            feedDone = true;
            opt.log.accept("reel door: pacer FAILED at record " + feed.recordsWalked() + " (fed=" + recordsFed
                    + " lastTick=" + lastTick + "): " + e);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            summary();
        }
    }

    /** Re-speak one record: split, filter (negative control), lane, round bump at MRST. */
    private void speak(CcwrDownFeed.DownRecord r) throws IOException {
        List<CcwrDownFeed.Inner> inner = CcwrDownFeed.split(r.payload());
        DatagramReliability c = core;
        for (CcwrDownFeed.Inner f : inner) {
            if (f.snap()) {
                if (opt.dropShipId >= 0L && carriesDroppedShip(f)) {
                    controlDropped++;
                    continue;
                }
                c.snapSend(f.bytes(), nowMillis());
                framesSnap++;
            } else {
                long giveUp = nowMillis() + CTRL_WINDOW_WAIT_MILLIS;
                boolean sent;
                while (!(sent = c.ctrlSend(f.bytes(), nowMillis())) && nowMillis() < giveUp && !closed) {
                    sleepQuiet(5L); // window full: the client acks within 20 ms — wait, never drop silently
                }
                if (!sent) {
                    ctrlRefused++;
                } else {
                    framesCtrl++;
                    if (f.message().kind() == ServerStreamMessage.Kind.MATCH_RESET) {
                        roundId = (roundId + 1) & 0xFF; // Barrier: bump AT MRST emission, after the spool
                    }
                }
            }
        }
    }

    private boolean carriesDroppedShip(CcwrDownFeed.Inner f) {
        ServerSnapshotFrame snap = f.message().frame();
        if (snap == null) {
            return false;
        }
        long t = snap.simTick();
        return t >= opt.dropFromTick && t <= opt.dropToTick && snap.entityById(opt.dropShipId) != null;
    }

    // ------------------------------------------------------------------ timer thread + sink

    private void timerLoop() {
        while (!closed) {
            DatagramReliability c = core;
            if (c != null) {
                c.onTimer(nowMillis());
            }
            sleepQuiet(TIMER_MILLIS);
            if (feedDone && byeSeen) {
                return;
            }
        }
    }

    private void emitFromCore(DatagramReliability.Emission emission) {
        DatagramFrameCodec.WireChannel channel = switch (emission.channel()) {
            case SNAP -> DatagramFrameCodec.WireChannel.SNAP;
            case CTRL -> DatagramFrameCodec.WireChannel.CTRL;
            case ACK -> DatagramFrameCodec.WireChannel.ACK;
        };
        byte[] datagram = DatagramFrameCodec.encode(channel, roundId, sessionId, emission.seq(),
                emission.ctrlAck(), emission.ctrlAckBits(), emission.snapHiSeen(), emission.payload());
        sendRaw(datagram, client);
    }

    private void sendRaw(byte[] datagram, SocketAddress to) {
        if (to == null) {
            return;
        }
        synchronized (sendLock) {
            try {
                socket.send(new DatagramPacket(datagram, datagram.length, to));
            } catch (IOException e) {
                sendFailures.incrementAndGet(); // AtomicLong (multi-thread writers)
            }
        }
    }

    private int welcomeFlags() {
        return (opt.matchFlags | HandshakeCodec.WELCOME_FLAG_SEAT_OBSERVER) & HandshakeCodec.WELCOME_FLAGS_DEFINED;
    }

    private void summary() {
        DatagramReliability c = core;
        opt.log.accept("reel door: SUMMARY hellosNot13Dropped=" + hellosNot13Dropped + " probes=" + probes
                + " refused=" + refused
                + " fed=" + recordsFed + " snap=" + framesSnap + " ctrl=" + framesCtrl
                + " clientSnapIn=" + clientSnapIn + " clientCtrlIn=" + clientCtrlIn + " clientAckIn=" + clientAckIn
                + " sendFailures=" + sendFailures.get() + " undecodable=" + dropped // AtomicLong read
                + " handlerFailures=" + handlerFailures + " lagMaxMs=" + lagMaxMillis + " round=" + roundId
                + " hs13Cookies=" + hs13Cookies
                + " hs13NoSecretSeals=" + hs13NoSecretSeals + " hs13SecretRefused=" + hs13SecretRefused
                + " hs13Opens=" + hs13OpenAttempts + " hs13Dropped=" + hs13Dropped + " hs13Unserved=" + hs13Unserved
                + (c != null ? " core=" + c.stats() : ""));
    }

    /** Stop the door: the socket closes (the socket thread exits), the pacer stops at its next
     * record, the reel handle closes. Safe to call twice. */
    public void close() {
        closed = true;
        socket.close();
        try {
            feed.close();
        } catch (IOException ignore) {
            // closing
        }
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Wire text is DATA: printable ASCII only, capped, before it hits a log row. */
    private static String printable(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            b.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return b.toString();
    }

    private static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
