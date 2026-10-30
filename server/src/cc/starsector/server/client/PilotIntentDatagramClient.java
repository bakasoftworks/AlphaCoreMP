package cc.starsector.server.client;

import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.runtime.AuthoritativeWorldSnapshotCodec;
import cc.starsector.server.runtime.AwsdNackFrameCodec;
import cc.starsector.server.runtime.AwsdSnapshotCodec;
import cc.starsector.server.runtime.ChatFrameCodec;
import cc.starsector.server.runtime.DatagramFrameCodec;
import cc.starsector.server.runtime.DatagramReliability;
import cc.starsector.server.runtime.EntityDespawnEventCodec;
import cc.starsector.server.runtime.FireDischargeDeclaration;
import cc.starsector.server.runtime.FireDischargeDeclarationCodec;
import cc.starsector.server.runtime.GameplayEdgeEventCodec;
import cc.starsector.server.runtime.HulkCutGeometryFrameCodec;
import cc.starsector.server.runtime.HulkFractureFrameCodec;
import cc.starsector.server.runtime.HulkPoseFrameCodec;
import cc.starsector.server.runtime.MatchOverFrameCodec;
import cc.starsector.server.runtime.MatchResetFrameCodec;
import cc.starsector.server.runtime.HandshakeCodec;
import cc.starsector.server.runtime.Handshake13;
import cc.starsector.server.runtime.Handshake13Flight;
import cc.starsector.server.runtime.Handshake13PairBudget;
import cc.starsector.server.runtime.PilotIntentFrame;
import cc.starsector.server.runtime.PilotIntentWireCodec;
import cc.starsector.server.runtime.PilotPortFile;
import cc.starsector.server.runtime.PostureFrameCodec;
import cc.starsector.server.runtime.ReservedControlFrameCodec;
import cc.starsector.server.runtime.RosterAppendFrameCodec;
import cc.starsector.server.runtime.SeatNameFrameCodec;
import cc.starsector.server.runtime.SeatStateFrameCodec;
import cc.starsector.server.runtime.SnapDeflateCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * The game-free UDP sibling of {@link PilotIntentSocketClient}
 * (the synthetic seat every drill/selftest leg drives; a
 * real second program, no engine, no shared memory). Where the TCP client is response-driven (one
 * intent per DOWN frame on the ordered stream), this one is clock-driven: HELLO/WELCOME admission,
 * an UP intent stream at a configurable Hz on the SNAP channel (latest-wins — conflation is
 * the contract), DOWN consumption of SNAP AWSS (latest-wins + the roundId barrier) and CTRL
 * in-order deliveries (GPEE/EDSP/SEAT/MRST/SNMS; reserved DESC/HOUS count-ignored),
 * PDCL riding CTRL, and the drill behaviors:
 * pause (outbound wire silence for a window — the {@code udp_silence} death leg), clean BYE
 * goodbye, and hard-exit (process halt mid-stream, the crash leg).
 *
 * <p>Codecs are the server's own: admission through the server's {@link Handshake13Flight} (it hands back the
 * {@link HandshakeCodec.WelcomePayload} record it opened — no parallel handshake codec), payload frames
 * through the existing PINT/PDCL/AWSS/GPEE/EDSP/MRST/SNMS codecs unchanged (the datagram supplies the frame boundary).
 *
 * <p><b>Version 13 only</b>: admission runs
 * the server's own {@link Handshake13Flight} (F1 → COOKIE13 → F2 → WELCOME13), so the credential in
 * {@code --seat-token} never crosses the wire readable; {@code --lane password|token} (default {@code password}) names
 * which credential FIELD that is (the flight's lane — never a door's say). This client holds NO version-12 encoder:
 * a run that hears silence times out, it never sends a version-12 HELLO (a downgrade an on-path party could force).
 * {@code --hs13 true} is accepted as a no-op (stage 3 deletes the flag); {@code --hs13 false} and any other value are
 * refused at start-up. The receipt's {@code hs=} row names the version the run spoke ({@code Handshake13.VERSION}). {@code --probe true} is the
 * version-13 PROBE ({@link #probe()}): a burst of {@code FLAG_PROBE} flights. A value-less {@code --hs13} /
 * {@code --lane} as the LAST argument is refused (never silently skipped). Every flight is {@code abandon()}ed on every
 * exit of its path.
 *
 *
 * <p>Threading mirrors the server endpoint: a reader thread feeds the {@link DatagramReliability}
 * core, a timer thread pumps retransmits/keepalives, the main thread runs the intent loop; the
 * lock-order rule is the same — core callbacks may take {@code stateLock}, nothing holding
 * {@code stateLock} calls into the core.
 *
 * <p><b>Round barrier (client-side):</b> SNAP applies only for the CURRENT roundId; a
 * next-round SNAP is BUFFERED (one slot, latest-wins) and applied on the CTRL-delivered MRST flip;
 * any other roundId drops + counter. The client never flips backwards — flips happen only on MRST
 * delivery (exactly-once via the reliability core), one increment each.
 *
 * <p><b>Edge-bit hold rule:</b> UP producers must hold the edge bits
 * ({@link PilotIntentFrame#USE_SHIP_SYSTEM}/{@link PilotIntentFrame#VENT_FLUX}) for at least
 * {@link #EDGE_BIT_MIN_HOLD_FRAMES} consecutive frames — datagram loss is conflation-shaped and a
 * 1-frame synthetic pulse can vanish. The {@code --vent-from}/{@code --system-from} lanes hold
 * from their start frame onward (an unbounded hold), which satisfies the rule by construction;
 * any future pulse-shaped lane must respect the constant.
 *
 * <p>Fail-closed like the TCP client: any error exits non-zero with a stack trace on stderr and a
 * receipt marked {@code connected=false} — but with the datagram counters still populated, so a
 * drill can assert the death class (e.g. {@code udp_silence}) from the receipt alone.
 */
public final class PilotIntentDatagramClient {

    /** The handshake's default HELLO deadline; {@code --connect-timeout-millis} (drill-only, >= 1) overrides it. */
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    /** HELLO retransmit cadence until a nonce-matching WELCOME. */
    private static final int HELLO_RETRY_MILLIS = 250;
    /** Default inbound-silence death bound (the TCP 60 s read-timeout mirror); drills shrink it. */
    private static final int DEFAULT_READ_DEADLINE_MILLIS = 60_000;
    private static final int DEFAULT_MAX_FRAMES = 100_000;
    /** Timer granularity — the server endpoint's value. */
    private static final long TIMER_GRANULARITY_MILLIS = 15L;
    /** How often the intent loop stats {@code --stop-file}. */
    private static final long STOP_FILE_POLL_MILLIS = 250L;
    /** Most lines ONE {@code --chat-file} drop sends — the door's chat bucket is
     * burst-4, so a fatter drop would only be rate-refused; the rest of the drop is discarded. */
    private static final int CHAT_FILE_MAX_LINES = 4;
    /** Bound on the goodbye CTRL flush + BYE-echo wait (the server's unarmed-flush mirror). */
    private static final long GOODBYE_BOUND_MILLIS = 5_000L;
    /** wire-contract note: minimum consecutive frames an UP edge bit must be held. */
    public static final int EDGE_BIT_MIN_HOLD_FRAMES = 5;
    private static final int RECEIVE_BUFFER_BYTES = 2_048;

    private record Options(String host, int port, long shipId, String clientId, String seatToken,
            String fingerprint, int selectedGroup, int strafeBurst, float brakeBelow, int accelFrom, int ventFrom,
            int fireFrom, int[] fireWindows, int systemFrom, int abilityFrom, int declEvery, int maxFrames,
            int intentHz, int pauseAfterFrames, long pauseMillis, int hardExitAfter, int readDeadlineMillis,
            Path receiptPath, String announceName, List<String> chatTexts, boolean probe,
            int probeBurst, int cloakFrom, int chatAfterSent, Path stopFile, int shieldFrom,
            List<Path> vsubFiles, String helloVariant, long awaitVackMillis, List<String> chatAfterVack,
            boolean vsubSerial, Path chatFile, int autofireMask, Handshake13Flight.Lane lane,
            int connectTimeoutMillis, int team, int helloFlags, int[] abilityWindow) {
        // helloFlags: 0 or a subset of 0x18 (the HELLO MagicLib report bits; a drill lever, never a kit sysprop);
        // abilityWindow: null or {slot 0..7, fromFrame >= 1, toFrame > from} = the held ability bit for one slot
        // over that frame window (S4b's seam drill rows 19-21 + 23; the hunk = its commit body).
        // lane: never null — the --lane credential FIELD of every version-13 flight (default PASSWORD).
        // connectTimeoutMillis: >= 1, the handshake's HELLO deadline (CONNECT_TIMEOUT_MILLIS unless a drill sets it).
        // team: 0..2, the --team pick the session HELLO carries (0 = the server picks; 1 = South; 2 = North; a probe
        // always sends 0).
        // abilityFrom: >= 0, the frameSeq from which mod-ability slot 0 is held; 0 = never.
    }

    /** Latest applied DOWN world state (written by the reader's SNAP callback, read by the intent
     * loop): frameSeq echoes the server simTick like the TCP client, aim = own bearing onto foe. */
    private record AimView(int simTick, float aimDeg) {
    }

    private final Options options;
    private final DatagramSocket socket;
    private final InetSocketAddress server;
    private final MessageDigest digest = newSha256();
    private final Thread reader;
    private final Thread timer;
    /** The door PROBE's collected listing frames by seqIndex (main thread only —
     * a probe never starts the reader). */
    private final java.util.TreeMap<Integer, cc.starsector.server.runtime.ListingFrameCodec.ListingDown>
            probeFrames = new java.util.TreeMap<>();
    /** Every sessionId-0 CTRL datagram the probe read (duplicates included — the
     * burst row pins {@code probe_datagrams == probe_frames}, i.e. ONE served reply). */
    private int probeDatagrams;
    /** One inflater per session (reader thread only — every SNAP apply path,
     * buffered-future promotion included, runs on the reliability callbacks); end() at close. */
    private final java.util.zip.Inflater snapInflater = SnapDeflateCodec.newInflater();

    /** Guards buffered-future-snap + movement-tracking state (core callbacks take it; nothing
     * holding it calls into the core — the endpoint lock-order rule). */
    private final Object stateLock = new Object();

    private volatile DatagramReliability reliability;
    private volatile long sessionId;
    private volatile int currentRoundId;
    private volatile long lastValidArrivalMillis;
    private volatile AimView aimView;
    private volatile IOException readerFailure;
    private volatile IOException sessionFailure;
    private volatile boolean peerSaidBye;
    private volatile boolean byeSent;
    private volatile boolean byeAcked;
    private volatile boolean closed;
    /** Pause drill: outbound datagrams (ALL of them — intents, acks, retransmits, BYE echoes) are
     * dropped at the send funnel until this wall-clock, simulating an outbound blackhole while the
     * inbound path stays live. 0 = never paused. */
    private volatile long pauseUntilMillis;

    // ---- counters (reader-thread written unless noted; read for the receipt) ----
    private volatile long droppedUndecodable;
    private volatile long droppedBadSession;
    private volatile long droppedBadSource;
    private volatile long droppedUnexpectedChannel;
    private volatile long welcomeDuplicates;
    private volatile long snapFreshApplied;
    private volatile long snapStaleRoundDropped;
    private volatile long snapFutureBuffered;
    private volatile long snapFutureApplied;
    private volatile long gpeeReceived;
    private volatile long edspReceived;
    private volatile long seatStateReceived;
    private volatile long hkpsReceived; // hulk-pose keyframes delivered on ctrl
    private volatile long hkfrReceived; // hulk-fracture edges delivered on ctrl
    private volatile long hkcgReceived; // hulk-cut-geometry records delivered on ctrl
    private volatile long vcfgReceived; // W12 L2: VCFG voice sheets on ctrl
    private volatile long rappReceived; // roster-append frames delivered on ctrl
    private volatile long rappRowsReceived; // total rows across those frames (dupes counted)
    /** Distinct appended wire ids seen in RAPP frames (reader thread writes, receipt reads) —
     * the wave-rig's both-sides witness: these must equal the serve log's wave_spawn wire_ids. */
    private final java.util.concurrent.ConcurrentSkipListSet<Long> rappWireIds =
            new java.util.concurrent.ConcurrentSkipListSet<>();
    private volatile long movrReceived; // serve-end MOVR verdicts delivered on ctrl
    /** MOVR verdict intake (reader thread deposits; {@link #matchOverRecords()} drains, the
     * receipt rows peek) — at most one per serve by the fan contract, a queue by the
     * deposit/drain law. */
    private final java.util.concurrent.ConcurrentLinkedQueue<MatchOverFrameCodec.MatchOverRecord>
            matchOverRecords = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile long reservedCtrlIgnored;
    private volatile long pstbReceived; // PSTB posture blocks delivered on ctrl
    private volatile String pstbLastTag = ""; // experiment tag of the newest PSTB ("" = none yet)
    private volatile long mrstReceived;
    private volatile long chatReceived; // CHTD relays delivered (echo included)
    private volatile String lastChat = ""; // "seatId|name|text" of the newest CHTD (receipt row)
    /** CatalogProof: post-admission LSTD collect — KIND_DEBUGSHIP frames of
     * the LATEST listingId only (latest-wins, the mod reader's law), keyed by seqIndex. Reader
     * thread writes, receipt reads (concurrent map = safe publication). */
    private final java.util.concurrent.ConcurrentSkipListMap<Integer,
            cc.starsector.server.runtime.ListingFrameCodec.ListingDown> listingFrames =
            new java.util.concurrent.ConcurrentSkipListMap<>();
    private volatile int listingLatestId = -1; // newest KIND_DEBUGSHIP listingId seen (-1 = none)
    private volatile long listingOtherIgnored; // non-DEBUGSHIP LSTD frames (tolerated, counted)
    private volatile long listingStaleDropped; // frames of a superseded listingId (latest-wins)
    /** VDEF variant-definition frames (the W7c B2 admission burst the PRODUCT
     * serve spools to every newcomer) — decoded fail-closed + counted, never folded. */
    private volatile long vdefReceived;
    /** W15 v0: VDEF frames whose id carries the server-minted {@code cc-v} prefix
     * — the runtime custom-variant fan reaching THIS live session (reader writes, receipt reads). */
    private final java.util.concurrent.ConcurrentSkipListSet<String> vdefCustomIds =
            new java.util.concurrent.ConcurrentSkipListSet<>();
    /** W15 v0: {@code --vsub-file} submissions sent (main thread) + the VACK
     * receipts collected by the reader ({@code seq:verdict:token:id}, arrival order). */
    private int vsubSent;
    private final java.util.concurrent.CopyOnWriteArrayList<String> vackLog =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long vackAccepted;
    private volatile long vackRejected;
    private volatile long aimpReceived; // AIMP aim fan, tolerated + counted
    /** Every OTHER {@link cc.starsector.server.runtime.DownFrameFamily} kind
     * (XFER, MMNT, MRER, CBTO, DIVE, VCDN, PWVW, …) advanced whole by its own codec read and
     * counted per leading magic — reader thread writes, receipt reads (sorted = deterministic). */
    private final java.util.concurrent.ConcurrentSkipListMap<Integer, Long> familyTolerated =
            new java.util.concurrent.ConcurrentSkipListMap<>();
    private volatile boolean stopFileSeen; // --stop-file ended the stream
    private volatile long pausedOutboundDropped; // incremented from any thread; ++ races lose at
                                                 // most a count under a drill's own pause — benign
    private int helloRetransmits; // main thread only (handshake)
    /** Door COOKIE challenges (own nonce) answered by
     * the handshake + the probe burst. Main thread only. */
    private int cookieChallenges;
    private volatile SeatNameFrameCodec.SeatNames seatNames;
    /** SNMR intake: latest record per seat (reader thread writes, receipt reads —
     * a takeover re-announce replaces its seat's entry). */
    private final java.util.concurrent.ConcurrentSkipListMap<Integer, SeatNameFrameCodec.SeatNameRecord>
            seatNameRecords = new java.util.concurrent.ConcurrentSkipListMap<>();
    private volatile long seatNameRecordsReceived;
    private volatile int ownFramesObserved;
    private volatile float ownMaxDisp;
    private volatile float ownMaxSpeed;
    /** Last observed own speed (reader-thread written in publishView, main-thread read by the
     * idle brake); 0 until the first own record arrives — no brake before evidence. */
    private volatile float ownLastSpeed;
    private float ownFirstX = Float.NaN; // reader thread only
    private float ownFirstY = Float.NaN;
    private volatile float lastAimDeg = Float.NaN;
    private int framesSent; // main thread only
    /** The deferred --chat-text lines went out (main thread only). */
    private boolean deferredChatSent;
    /** Chat lines the {@code --chat-file} lane has sent (main thread only). */
    private int chatFileLinesSent;

    /** The opt-in chat line(s) on post-admission CTRL like the announce; the server
     * fans PLAIN chat to ALL live sessions including this one (echo = delivery confirm) while
     * /commands dispatch at the door (replies only, never fanned). Sent at admission, or — with
     * {@code --chat-after-sent N} — from the intent pump once N frames have been sent. */
    private void sendChatLines() throws IOException {
        // VSUBs BEFORE the chat lines: both ride the one in-order reliable CTRL lane, so a
        // `/ship <local id>` typed after a submission reaches the door with the submission already
        // deposited — the pending-hold path, not a premature "unknown variant".
        sendVariantSubmits();
        for (String chatLine : options.chatTexts()) {
            ctrlSendBounded(ChatFrameCodec.encodeUp(chatLine), "chat text");
        }
    }

    /** {@code --await-vack-millis}: block the main thread until every VSUB has its VACK (the
     * reader counts them) or the bound elapses — never a failure, the receipt tells the truth. */
    private void awaitVacks() throws InterruptedException {
        long bound = options.awaitVackMillis();
        if (bound <= 0L || vsubSent == 0) {
            return;
        }
        long deadline = nowMillis() + bound;
        while (vackAccepted + vackRejected < vsubSent && nowMillis() < deadline) {
            Thread.sleep(20L);
        }
        System.out.println("pilot-datagram-client vack_await sent=" + vsubSent + " accepted=" + vackAccepted
                + " rejected=" + vackRejected + " timed_out=" + (vackAccepted + vackRejected < vsubSent));
    }

    /** W15 v0: every {@code --vsub-file} as ONE VSUB frame (the client
     * half's bytes, produced by the SERVER-tree codec — the game-free first proof of the door →
     * policy → ledger → sim-thread registrar → VDEF fan → VACK chain). The file is the engine's
     * own {@code .variant} grammar ({@link cc.starsector.server.runtime.VariantCatalogStore}
     * parser); goalVariant is forced false (a policy law, not a shape law). A file that cannot
     * encode is a loud local refusal — the wire never sees it. */
    /** The synthetic client has no engine, so its VSUB indexes against the
     * SERVER's boot dump ({@code <portFile>.id-tables.json}) named by {@code -Dcc.client.idTables};
     * absent = a loud local refusal (the wire never sees a guessed index). Loaded once, lazily
     * (org.json rides only this path, the {@code --vsub-file} idiom). */
    private cc.starsector.server.runtime.IdTables submitIdTables() throws IOException {
        if (submitIdTables == null) {
            String path = System.getProperty("cc.client.idTables", "");
            if (path.isBlank()) {
                throw new IOException("--vsub-file needs -Dcc.client.idTables=<file> (the server's"
                        + " <portFile>.id-tables.json dump; game-data ids ride as table indices)");
            }
            submitIdTables = cc.starsector.server.runtime.IdTablesJsonFile.read(new java.io.File(path)); // fail-closed: missing / oversize / vtab mismatch throw
            System.out.println("pilot-datagram-client id_tables " + submitIdTables.census());
        }
        return submitIdTables;
    }

    private cc.starsector.server.runtime.IdTables submitIdTables;

    private void sendVariantSubmits() throws IOException {
        for (Path file : options.vsubFiles()) {
            if (Files.size(file) > cc.starsector.server.runtime.VariantCatalogStore.MAX_FILE_BYTES) {
                throw new IOException("--vsub-file exceeds " + cc.starsector.server.runtime.VariantCatalogStore.MAX_FILE_BYTES
                        + " B: " + file);
            }
            String text = Files.readString(file);
            // This class must carry NO org.json reference of its own (a catch type is resolved at
            // class VERIFICATION — the endpoint smoke's bare-cp client JVM died with
            // NoClassDefFoundError before main): the JSON parse lives in the store, whose
            // class loads only here, only with --vsub-file present (json.jar on the cp then).
            cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition parsed;
            try {
                parsed = cc.starsector.server.runtime.VariantCatalogStore.parseVariantText(text);
            } catch (IllegalArgumentException badFile) {
                throw new IOException("--vsub-file not a lawful .variant: " + file + ": " + badFile.getMessage(), badFile);
            }
            cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition submitted =
                    new cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition(
                            parsed.variantId(), parsed.hullId(), parsed.displayName(), false,
                            parsed.fluxVents(), parsed.fluxCapacitors(), parsed.hullMods(), parsed.permaMods(),
                            parsed.sMods(), parsed.sModdedBuiltIns(), parsed.suppressedMods(), parsed.wings(),
                            parsed.tags(), parsed.weaponGroups(), parsed.modules());
            int seq = vsubSent & cc.starsector.server.runtime.VariantSubmitFrameCodec.SUBMIT_SEQ_MAX;
            byte[] frame = cc.starsector.server.runtime.VariantSubmitFrameCodec.encode(
                    new cc.starsector.server.runtime.VariantSubmitFrameCodec.VariantSubmit(seq, submitted),
                    submitIdTables());
            ctrlSendBounded(frame, "variant submit");
            vsubSent++;
            System.out.println("pilot-datagram-client vsub_sent seq=" + seq
                    + " local_id=" + submitted.variantId() + " hull=" + submitted.hullId()
                    + " bytes=" + frame.length + " file=" + file.getFileName());
            if (options.vsubSerial()) {
                awaitVackSettled(vsubSent);
            }
        }
    }

    /** {@code --vsub-serial true}: block until the {@code n}-th VSUB has its VACK
     * before the next file goes out. The ledger admits ONE pending body per author
     * ({@code SubmittedVariantLedger.PENDING_PER_AUTHOR}); a burst of door-passing files would have
     * every file after the first refused {@code custom_rate_limited} at the gate — never reaching
     * the engine gates the deny fixtures exist to exercise. Bound = {@code --await-vack-millis}
     * (or 5 s when unset); a timeout is LOUD here and honest in the receipt, never a failure. */
    private void awaitVackSettled(int n) {
        long bound = options.awaitVackMillis() > 0L ? options.awaitVackMillis() : 5000L;
        long deadline = nowMillis() + bound;
        try {
            while (vackAccepted + vackRejected < n && nowMillis() < deadline) {
                Thread.sleep(20L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        System.out.println("pilot-datagram-client vsub_serial sent=" + n + " settled="
                + (vackAccepted + vackRejected >= n));
    }
    private int brakeFrames; // main thread only — intent frames that carried the idle-brake bit
    // Fire-window levers (main thread only): wrapping 0..255 rising-edge counter +
    // previous-sample backing, mirroring the real producer's edge detect.
    private int fireEdgeSeq;
    private boolean fireEdgePrevHeld;
    private int fireRisingEdges;
    /** Frames the --ability-window bit was held (the drill's receipt row ability_window_held=). */
    private int abilityWindowHeld;
    private int declSent;
    private int declSeq;
    private int startingRoundId;
    private byte[] bufferedFutureSnap; // guarded by stateLock
    private int bufferedFutureRound;

    /** The ghost seat's reassembly law (AWSS
     * chunk/fragment store + AWSD anchors + NACK queue), extracted to
     * {@link MirrorChunkMerger} so the merger-equivalence gate can drive it.
     * Store methods are reader-thread-only, fed in {@link #applySnapshot} below decode (the
     * buffered-future-snap slot stays raw bytes); EDSP evicts in {@link #onCtrlDelivered},
     * MRST clears in {@link #onMatchReset}; the NACK queue drains on the intent-pump thread
     * — an inline reader-thread {@code ctrlSendBounded} would starve the very ACK processing
     * that frees the CTRL window (this class's reader is also its ACK path). */
    private final MirrorChunkMerger merger = new MirrorChunkMerger();
    private volatile long snapAwsdNackTx; // SNNK frames sent on CTRL (post-drain, post-encode)
    private String sentSha256Hex; // digest() RESETS the digest — compute once, reuse everywhere

    private PilotIntentDatagramClient(Options options) throws IOException {
        this(options, true);
    }

    /** {@code openSocket=false} = the socket-less {@link CtrlDemuxProbe} form (never sends,
     * never starts the threads); the live client always opens one. */
    private PilotIntentDatagramClient(Options options, boolean openSocket) throws IOException {
        this.options = options;
        this.server = new InetSocketAddress(options.host(), options.port());
        if (this.server.isUnresolved()) {
            throw new IOException("server address unresolved: " + options.host());
        }
        this.socket = openSocket ? new DatagramSocket() : null;
        this.reader = new Thread(this::readLoop, "pilot-datagram-client-reader");
        this.reader.setDaemon(true);
        this.timer = new Thread(this::timerLoop, "pilot-datagram-client-timer");
        this.timer.setDaemon(true);
    }

    /**
     * Contract seam: a socket-less instance whose CTRL demux
     * ({@link #onCtrlDelivered}) is driven directly. The constructor is Options-private and
     * {@code main} exits the JVM, so the suite could not otherwise reach the demux without
     * reflection. Never sends, never starts the reader/timer threads.
     */
    public static final class CtrlDemuxProbe {
        private final PilotIntentDatagramClient client;

        private CtrlDemuxProbe(PilotIntentDatagramClient client) {
            this.client = client;
        }

        public static CtrlDemuxProbe create() throws IOException {
            Options options = new Options("127.0.0.1", 1, 1L, "ctrl-demux-probe", "", "", 0, 0,
                    0f, 0, 0, 0, new int[0], 0, 0, 0, 1, 60, 0, 0L, 0, DEFAULT_READ_DEADLINE_MILLIS,
                    null, "", List.of(), false, 1, 0, 0, null, 0, List.of(), "", 0L, List.of(), false,
                    null, -1, null, CONNECT_TIMEOUT_MILLIS, 0, 0, null);
            return new CtrlDemuxProbe(new PilotIntentDatagramClient(options, false));
        }

        /** One CRC-valid CTRL payload as the reliability core would deliver it. */
        public void deliver(long seq, byte[] payload) {
            client.onCtrlDelivered(seq, payload);
        }

        /** The demux's terminal failure ({@code null} = the session is still live). */
        public IOException failure() {
            return client.sessionFailure;
        }

        public void clearFailure() {
            client.sessionFailure = null;
        }

        public long aimpReceived() {
            return client.aimpReceived;
        }

        /** The receipt's {@code family_tolerated=} value ({@code ""} = none). */
        public String familyToleratedRow() {
            return client.familyToleratedRow();
        }
    }

    public static void main(String[] args) {
        Path receiptPath = null;
        PilotIntentDatagramClient client = null;
        try {
            Options options = parse(args);
            receiptPath = options.receiptPath();
            client = new PilotIntentDatagramClient(options);
            if (options.probe()) {
                // The door PROBE — no session, no intent stream, no goodbye.
                client.probe();
                List<String> lines = client.probeReceiptLines();
                // hs= rides the receipt FILE only — the OK line below prints `lines` unchanged.
                List<String> receipt = new ArrayList<>(lines);
                receipt.add(client.hsReceiptRow());
                writeReceipt(receiptPath, receipt);
                System.out.println("pilot-intent datagram probe OK: " + String.join(" ",
                        lines.subList(6, lines.size())));
                System.exit(0);
            }
            boolean byeClean = client.run();
            writeReceipt(receiptPath, client.receiptLines(true, ""));
            System.out.println("pilot-intent datagram client OK: frames_sent=" + client.framesSent
                    + " last_aim_deg=" + client.lastAimDeg
                    + " sent_sha256=" + client.sentSha256()
                    + " rounds_flipped=" + client.mrstReceived + " bye_clean=" + byeClean);
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            if (receiptPath != null) {
                try {
                    List<String> lines = client != null
                            ? client.receiptLines(false, errorText(e))
                            : minimalFailureLines(errorText(e));
                    writeReceipt(receiptPath, lines);
                } catch (IOException ignore) {
                    // best effort
                }
            }
            System.exit(1);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private static Options parse(String[] args) throws IOException {
        String host = null;
        int port = -1;
        long shipId = -1L;
        String clientId = "datagram-client";
        String seatToken = "";
        String fingerprint = "";
        String fingerprintFile = null;
        String foldVtab = null;
        String foldModset = null;
        int selectedGroup = 0;
        // The wire autofire mask (PilotIntentFrame v4, bit g = group g autofiring).
        // Default -1 = ABSENT: the server skips its autofire diff, so every existing producer, recording
        // and ladder stays byte-identical. The server DISARMS the variant's build-time autofire groups at
        // fly-in (PilotServeSession fly_in_autofire_disarm) and re-arms only from this mask, so a synthetic
        // seat whose own PD must fire (the arena rig's guardian beam-vs-missile proof) passes the mask
        // explicitly — with its group DESELECTED (--selected-group -2): a SELECTED autofire group is manual
        // under the engine's personal-control law (PilotCommandApplier#applyAutofireMask).
        int autofireMask = -1;
        int strafeBurst = 0;
        float brakeBelow = 0f;
        int accelFrom = 0;
        int ventFrom = 0;
        int fireFrom = 0;
        boolean fireFromSet = false;
        String fireWindowSpec = null;
        int systemFrom = 0;
        int abilityFrom = 0;
        int helloFlags = 0;
        String abilityWindowSpec = null;
        int declEvery = 0;
        int maxFrames = DEFAULT_MAX_FRAMES;
        // The HARNESS client's send cadence defaults to the configured sim tick rate
        // (the real client is server-paced and needs no knob); an explicit --intent-hz still wins.
        int intentHz = cc.starsector.server.runtime.SimTickRate.ticksPerSecond();
        int pauseAfterFrames = 0;
        long pauseMillis = 0L;
        int hardExitAfter = 0;
        int readDeadlineMillis = DEFAULT_READ_DEADLINE_MILLIS;
        int connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS;
        Path receiptPath = null;
        // Graceful stop — the intent loop polls this path and, once it exists,
        // ends the stream with the clean BYE goodbye so the receipt is WRITTEN (main(): run() ->
        // writeReceipt). A harness that pid-kills a surviving seat loses the receipt.
        Path stopFile = null;
        Path chatFile = null;
        String portFile = null;
        String announceName = "";
        List<String> chatTexts = new ArrayList<>();
        List<Path> vsubFiles = new ArrayList<>();
        String helloVariant = "";
        long awaitVackMillis = 0L;
        List<String> chatAfterVack = new ArrayList<>();
        boolean vsubSerial = false;
        boolean probe = false;
        int probeBurst = 1;
        // Version 13 is the ONLY handshake (THE FLIP) — --lane alone
        // picks the credential FIELD, default password; --hs13 is parsed only to refuse anything but the no-op true.
        Handshake13Flight.Lane lane = Handshake13Flight.Lane.PASSWORD;
        // Lash proof rig: hold the PHASE_CLOAK state-mirror bit (wire bit 11) from
        // this server tick onward (0 = never) — the server's applyCloakBit mismatch lane raises
        // the cloak on a phase hull and re-raises it after every engine-forced OUT.
        int cloakFrom = 0;
        // Specimen arena decoy: hold the SHIELD_ON state-mirror bit (wire bit 10)
        // from this intent frame onward (0 = never) — the server's shield hold lane raises the shield
        // on a shield hull and re-raises it after every engine-forced OUT (overload, vent).
        int shieldFrom = 0;
        // Defer the --chat-text lines until this many intent frames have been SENT
        // (0 = at admission, the original chat posture). /teleport needs a LIVE hull (the seat's ship
        // is pending-add at admission), so an admin rig sends its verbs a few seconds in.
        int chatAfterSent = 0;
        // --team 0|1|2, the session HELLO's team pick (0 = the server
        // picks, the default); any other text is refused naming the flag, never clamped to a team
        int team = 0;
        // The loop below never visits a trailing key, so a
        // value-less --hs13 / --lane would be silently skipped (a dangling --lane = the default lane) — refused,
        // naming the flag (never a value).
        if (args.length % 2 == 1 && List.of("--hs13", "--lane", "--team").contains(args[args.length - 1])) {
            throw new IllegalArgumentException(args[args.length - 1] + " needs a value (a trailing flag is never skipped)");
        }
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--port-file" -> portFile = args[i + 1];
                case "--ship-id" -> shipId = Long.parseLong(args[i + 1]);
                case "--client-id" -> clientId = args[i + 1];
                case "--seat-token" -> seatToken = args[i + 1];
                case "--fingerprint" -> fingerprint = args[i + 1];
                // Echo the armed door's expected composite from the SAME minted
                // sidecar the server reads per serve (<descriptor>.fingerprint) — the designed
                // tests/doctoring override lane; the door still string-compares fail-closed.
                case "--fingerprint-file" -> fingerprintFile = args[i + 1];
                // A SESSION claim must present the id-table FOLD of the
                // sidecar composite (DoorFingerprintExpectations.acceptsSession; the raw
                // print admits a PROBE only — a recorded seat died presented_fold=raw). The unattended
                // rig echoes the door's own bind row (`npeer-udp-door fingerprint=armed vtab=<hex>
                // modset=<hex>`) so the seat folds exactly the way the server did.
                case "--fold-vtab" -> foldVtab = args[i + 1];
                case "--fold-modset" -> foldModset = args[i + 1];
                case "--selected-group" -> selectedGroup = Integer.parseInt(args[i + 1]);
                case "--autofire-mask" -> autofireMask = Integer.parseInt(args[i + 1]);
                case "--strafe-burst" -> strafeBurst = Integer.parseInt(args[i + 1]);
                // Idle brake — held DECELERATE while the observed own speed is
                // above this threshold (units/s; 0 = off), neutral once parked.
                case "--brake-below" -> brakeBelow = Float.parseFloat(args[i + 1]);
                case "--accel-from" -> accelFrom = Integer.parseInt(args[i + 1]);
                case "--vent-from" -> ventFrom = Integer.parseInt(args[i + 1]);
                case "--fire-from" -> { fireFrom = Integer.parseInt(args[i + 1]); fireFromSet = true; }
                // Bounded fire window(s) "from:to[,from:to…]" in frameSeq (= server
                // simTick) space — held while from <= frameSeq < to, so `to` IS the release tick
                // (the falling edge the tap-integrity latch machine needs; --fire-from stays the
                // degenerate unbounded window). Multiple windows = hold/release/re-hold cycles.
                case "--fire-window" -> fireWindowSpec = args[i + 1];
                case "--system-from" -> systemFrom = Integer.parseInt(args[i + 1]);
                case "--ability-from" -> abilityFrom = Integer.parseInt(args[i + 1]);
                case "--hello-flags" -> helloFlags = Integer.decode(args[i + 1]);
                case "--ability-window" -> abilityWindowSpec = args[i + 1];
                case "--decl-every" -> declEvery = Integer.parseInt(args[i + 1]);
                case "--max-frames" -> maxFrames = Integer.parseInt(args[i + 1]);
                case "--intent-hz" -> intentHz = Integer.parseInt(args[i + 1]);
                // Drill legs: pause (outbound silence window), hard-exit (crash mid-stream).
                case "--pause-after" -> pauseAfterFrames = Integer.parseInt(args[i + 1]);
                case "--pause-millis" -> pauseMillis = Long.parseLong(args[i + 1]);
                case "--hard-exit-after" -> hardExitAfter = Integer.parseInt(args[i + 1]);
                case "--read-deadline-millis" -> readDeadlineMillis = Integer.parseInt(args[i + 1]);
                // The HELLO deadline of BOTH handshakes (a drill's silent-door row waits 2 s, not 15).
                case "--connect-timeout-millis" -> connectTimeoutMillis = Integer.parseInt(args[i + 1]);
                case "--receipt" -> receiptPath = Path.of(args[i + 1]);
                // Seat-name posture (opt-in; default "" = bytes untouched): announce a seat name on
                // CTRL right after admit — the retired serve() lane (git history) with the event
                // lane ON blocked its SNAM window (clientWaitMillis) until an announce arrived or
                // the window lapsed; no SNAM window exists at N.
                case "--announce-name" -> announceName = args[i + 1];
                // Chat posture (opt-in; default none): chat line(s) on CTRL right after the
                // announce — the codec law gates the text pre-send (fail-fast local).
                // REPEATABLE: the CatalogProof leg rides TWO lines
                // (/debugships + /spawnfoe) under the door's burst-4 chat bucket.
                case "--chat-text" -> chatTexts.add(args[i + 1]);
                // The door PROBE twin (no session) — --probe-burst N sends N
                // PROBE HELLOs back-to-back from the ONE socket (the per-source budget drill).
                case "--probe" -> probe = Boolean.parseBoolean(args[i + 1]);
                case "--probe-burst" -> probeBurst = Integer.parseInt(args[i + 1]);
                // STRICT — only the no-op `true` passes (an asker of version 12 is told so, loud).
                case "--hs13" -> requireHs13NoOp(args[i + 1]);
                case "--lane" -> lane = laneOf(args[i + 1]);
                case "--team" -> team = teamOf(args[i + 1]);
                case "--cloak-from" -> cloakFrom = Integer.parseInt(args[i + 1]);
                case "--shield-from" -> shieldFrom = Integer.parseInt(args[i + 1]);
                case "--chat-after-sent" -> chatAfterSent = Integer.parseInt(args[i + 1]);
                case "--stop-file" -> stopFile = Path.of(args[i + 1]);
                // A HARNESS-FED chat lane — the intent loop polls this path (the
                // --stop-file cadence) and, when it holds text, sends its first lines as chat and
                // DELETES it (consume-on-read, the operator house file's law). A rig that must
                // sequence a player verb after a server row ("/suicide" only once the team
                // switch is logged) gets ordering by construction; --chat-after-sent is a TIMER.
                case "--chat-file" -> chatFile = Path.of(args[i + 1]);
                // W15 v0 (REPEATABLE): a `.variant` JSON file submitted as ONE VSUB on
                // post-admission CTRL, on the --chat-text schedule (immediately, or after
                // --chat-after-sent N frames); goalVariant is forced false (policy law), the file's
                // variantId rides as the client-local correlation id. The VACK lands in the receipt.
                case "--vsub-file" -> vsubFiles.add(Path.of(args[i + 1]));
                // W15 v0 step 3(c): the HELLO v8 variantId tail (a join-window pick;
                // a custom's LOCAL id is HELD by the door until its VSUB is accepted) + an optional
                // wait for every VSUB's VACK before the intent stream starts — models the real
                // client, whose first PINT follows the combat load by seconds, so the seat's FIRST
                // fly-in reads the seeded custom instead of racing the sim-thread drain.
                case "--hello-variant" -> helloVariant = args[i + 1];
                case "--await-vack-millis" -> awaitVackMillis = Long.parseLong(args[i + 1]);
                // W15 v0 step 3(f) re-proof (REPEATABLE): a chat line sent AFTER the
                // VACK await (i.e. once the seat's submissions have their verdicts) — `/customs`
                // listing an accepted custom needs the ACCEPT to have landed first.
                case "--chat-after-vack" -> chatAfterVack.add(args[i + 1]);
                // Wait for each VSUB's VACK before the next --vsub-file goes out (the
                // ledger's ONE-pending-per-author law would gate-refuse a burst as custom_rate_limited).
                case "--vsub-serial" -> vsubSerial = Boolean.parseBoolean(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if ((host == null || port < 0) && portFile != null) {
            String[] endpoint = PilotPortFile.read(portFile);
            if (endpoint == null) {
                throw new IllegalArgumentException("--port-file unreadable or incomplete: " + portFile);
            }
            if (host == null) { host = endpoint[0]; }
            if (port < 0) { port = Integer.parseInt(endpoint[1]); }
        }
        if (host == null || port < 0 || shipId < 0L || receiptPath == null) {
            throw new IllegalArgumentException("required: (--host --port | --port-file) --ship-id"
                    + " --receipt [--client-id --seat-token --fingerprint --fingerprint-file --fold-vtab --fold-modset"
                    + " --selected-group --autofire-mask --strafe-burst --brake-below --accel-from --vent-from"
                    + " --fire-from --fire-window --system-from --ability-from --cloak-from --shield-from --decl-every"
                    + " --hello-flags --ability-window"
                    + " --max-frames"
                    + " --intent-hz --pause-after --pause-millis --hard-exit-after"
                    + " --read-deadline-millis --connect-timeout-millis --chat-text --chat-after-sent"
                    + " --stop-file --vsub-file"
                    + " --hello-variant --await-vack-millis --chat-after-vack --vsub-serial"
                    + " --chat-file --lane --hs13]; every join / --probe true speaks version " + Handshake13.VERSION + " only; --lane"
                    + " password|token = which credential --seat-token holds (default password); --hs13 true is"
                    + " accepted as a no-op, --hs13 false is refused");
        }
        if (cloakFrom < 0 || chatAfterSent < 0 || shieldFrom < 0 || abilityFrom < 0) {
            throw new IllegalArgumentException(
                    "--cloak-from / --chat-after-sent / --shield-from / --ability-from must be >= 0: "
                    + cloakFrom + " / " + chatAfterSent + " / " + shieldFrom + " / " + abilityFrom);
        }
        if ((helloFlags & ~(Handshake13.FLAG_MAGIC_UP | Handshake13.FLAG_MAGIC_REPLICA)) != 0) {
            throw new IllegalArgumentException("--hello-flags must be 0 or a subset of 0x18 (the MagicLib report"
                    + " bits): 0x" + Integer.toHexString(helloFlags));
        }
        int[] abilityWindow = null;
        if (abilityWindowSpec != null) {
            String[] parts = abilityWindowSpec.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("--ability-window must be slot:fromFrame:toFrame: " + abilityWindowSpec);
            }
            abilityWindow = new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
            if (abilityWindow[0] < 0 || abilityWindow[0] > 7 || abilityWindow[1] < 1 || abilityWindow[2] <= abilityWindow[1]) {
                throw new IllegalArgumentException("--ability-window: slot 0..7, from >= 1, to > from: " + abilityWindowSpec);
            }
        }
        if (autofireMask < -1) {
            throw new IllegalArgumentException("--autofire-mask must be >= -1 (-1 = absent): " + autofireMask);
        }
        if (fingerprintFile != null) {
            if (!fingerprint.isEmpty()) {
                throw new IllegalArgumentException("--fingerprint and --fingerprint-file are"
                        + " mutually exclusive (one HELLO fingerprint source)");
            }
            fingerprint = ContentFingerprints.parseSidecarFingerprint(
                    Files.readAllLines(Path.of(fingerprintFile)), fingerprintFile);
        }
        if (foldVtab != null || foldModset != null) {
            if (foldVtab == null) {
                throw new IllegalArgumentException("--fold-modset needs --fold-vtab (the table is the"
                        + " fold's precondition, DoorFingerprintExpectations.armed)");
            }
            if (fingerprint.isEmpty()) {
                throw new IllegalArgumentException("--fold-vtab needs a composite to fold"
                        + " (--fingerprint or --fingerprint-file)");
            }
            // The same three-way fold the door armed with (ContentFingerprints.foldContent); a
            // wrong digest folds differently and the door refuses it — loud, never a bypass.
            fingerprint = ContentFingerprints.foldContent(fingerprint, foldVtab, foldModset);
        }
        int[] fireWindows = new int[0];
        if (fireWindowSpec != null) {
            if (fireFromSet) {
                throw new IllegalArgumentException("--fire-from and --fire-window are mutually"
                        + " exclusive (one fire schedule source)");
            }
            String[] parts = fireWindowSpec.split(",");
            fireWindows = new int[parts.length * 2];
            int prevTo = 0;
            for (int w = 0; w < parts.length; w++) {
                String[] edge = parts[w].split(":");
                if (edge.length != 2) {
                    throw new IllegalArgumentException("--fire-window entry must be from:to; got '"
                            + parts[w] + "' in " + fireWindowSpec);
                }
                int from = Integer.parseInt(edge[0].trim());
                int to = Integer.parseInt(edge[1].trim());
                if (from < prevTo || to <= from) {
                    throw new IllegalArgumentException("--fire-window windows must be ascending,"
                            + " non-overlapping, from < to (frameSeq ticks); got '" + parts[w]
                            + "' after to=" + prevTo + " in " + fireWindowSpec);
                }
                fireWindows[w * 2] = from;
                fireWindows[w * 2 + 1] = to;
                prevTo = to;
            }
        }
        if (brakeBelow < 0f || !Float.isFinite(brakeBelow)) {
            throw new IllegalArgumentException("--brake-below must be a finite speed >= 0"
                    + " (units/s; 0 disables): " + brakeBelow);
        }
        if (intentHz < 1 || intentHz > 1_000) {
            throw new IllegalArgumentException("--intent-hz must be 1..1000: " + intentHz);
        }
        if (pauseAfterFrames < 0 || pauseMillis < 0L || hardExitAfter < 0
                || readDeadlineMillis < 1) {
            throw new IllegalArgumentException("drill/liveness knobs must be non-negative"
                    + " (read deadline >= 1): --pause-after " + pauseAfterFrames
                    + " --pause-millis " + pauseMillis + " --hard-exit-after " + hardExitAfter
                    + " --read-deadline-millis " + readDeadlineMillis);
        }
        // The read-deadline sibling's floor, no upper cap;
        // the text names the flag + its int value, never anything else.
        if (connectTimeoutMillis < 1) {
            throw new IllegalArgumentException("--connect-timeout-millis must be >= 1: " + connectTimeoutMillis);
        }
        return new Options(host, port, shipId, clientId, seatToken, fingerprint, selectedGroup,
                strafeBurst, brakeBelow, accelFrom, ventFrom, fireFrom, fireWindows, systemFrom, abilityFrom, declEvery,
                maxFrames, intentHz, pauseAfterFrames, pauseMillis, hardExitAfter, readDeadlineMillis,
                receiptPath, announceName, List.copyOf(chatTexts), probe, Math.max(1, probeBurst),
                cloakFrom, chatAfterSent, stopFile, shieldFrom, List.copyOf(vsubFiles), helloVariant,
                awaitVackMillis, List.copyOf(chatAfterVack), vsubSerial, chatFile, autofireMask,
                lane, connectTimeoutMillis, team, helloFlags, abilityWindow);
    }

    /** {@code --hs13}: exactly {@code true} is accepted, as a no-op (the current version is the only handshake; stage 3 of THE
     * FLIP deletes the flag); {@code false} is refused naming why — this client cannot speak version 12 — and any
     * other value is refused without echoing it (a misplaced argument could be the secret).
     * */
    private static void requireHs13NoOp(String value) {
        if ("true".equals(value)) {
            return;
        }
        if ("false".equals(value)) {
            throw new IllegalArgumentException("--hs13 false is refused: this client speaks version " + Handshake13.VERSION + " only (version"
                    + " 12 is gone since THE FLIP); omit --hs13, or pass --hs13 true (a no-op)");
        }
        throw new IllegalArgumentException("--hs13 must be true (a no-op: version " + Handshake13.VERSION + " is the only handshake)");
    }

    /** {@code --team}: exactly {@code 0}, {@code 1} or {@code 2}; anything else an IAE naming the flag, the value never
     * echoed (a misplaced argument could be the secret). */
    static int teamOf(String value) {
        if (value != null && value.length() == 1 && value.charAt(0) >= '0' && value.charAt(0) <= '2') {
            return value.charAt(0) - '0';
        }
        throw new IllegalArgumentException("--team must be 0, 1 or 2 (0 = the server picks)");
    }

    /** {@code --lane}: the credential FIELD {@code --seat-token} fills. The value is never echoed — a misplaced
     * argument could be the secret itself. */
    private static Handshake13Flight.Lane laneOf(String value) {
        return switch (value) {
            case "password" -> Handshake13Flight.Lane.PASSWORD;
            case "token" -> Handshake13Flight.Lane.TOKEN;
            default -> throw new IllegalArgumentException("--lane must be password or token");
        };
    }

    // ------------------------------------------------------------------ run

    /** Admit, stream, goodbye. Returns whether the goodbye completed clean (BYE echoed / peer
     * initiated); throws on any terminal failure — admission reject, silence death, CTRL stall. */
    private boolean run() throws IOException, InterruptedException {
        handshake13();
        reader.start();
        timer.start();
        if (!options.announceName().isEmpty()) {
            // The ONE SNAM announce, immediately after admit and before the intent stream — the
            // mod session's posture (PredictReconcileDatagramSession.sendSeatName); reliable CTRL,
            // so the reader+timer must already be pumping retransmits.
            ctrlSendBounded(SeatNameFrameCodec.encodeAnnounce(options.shipId(), options.announceName()),
                    "seat-name announce");
        }
        if (options.chatAfterSent() == 0) {
            sendChatLines();
            awaitVacks();
            // The post-verdict chat lines (`/customs` after the ACCEPT), same lane.
            for (String chatLine : options.chatAfterVack()) {
                ctrlSendBounded(ChatFrameCodec.encodeUp(chatLine), "chat after vack");
            }
        }
        streamIntents();
        return goodbye();
    }

    /** The admit tail of {@link #handshake13()} (an ADMITTED event's opened WELCOME13): session id 0 is a protocol
     * break; else adopt the session + round and hand the socket to the reader. Main thread. */
    private void adoptAdmittedWelcome(HandshakeCodec.WelcomePayload welcome) throws IOException {
        if (welcome.sessionId() == 0L) {
            throw new IOException("pilot datagram WELCOME admitted with session id 0"
                    + " (protocol break — 0 is the no-session sentinel)");
        }
        sessionId = welcome.sessionId();
        startingRoundId = welcome.startingRoundId();
        currentRoundId = welcome.startingRoundId();
        reliability = new DatagramReliability(DatagramReliability.Config.defaults(),
                this::emitFromCore, this::onCtrlDelivered, this::onSnapDelivered);
        lastValidArrivalMillis = nowMillis();
        socket.setSoTimeout(0); // the reader thread blocks from here on
    }

    /**
     * The handshake (version 13 only): ONE {@link Handshake13Flight} — the first send is
     * {@code start}, every {@value #HELLO_RETRY_MILLIS} ms of silence re-sends {@code retransmitOrNull()} (identical
     * bytes, counted in {@code hello_retransmits}), every WELCOME-channel payload from the server address goes to
     * {@code onWelcome}: SEND = send the F2 now (+ {@code cookie_challenges}); HELD = the pair budget refused a new door
     * share pair, or the flight is at its {@code SEAL_CAP} (only a silence resend lifts it, so under an inbound stream
     * it stays capped to the deadline), keep resending what was sent; IGNORE = not ours / a duplicate challenge / a WELCOME13 that did not
     * open ({@code dropped_undecodable}); REJECTED = the "pilot datagram admission rejected: " text, ADMITTED =
     * {@link #adoptAdmittedWelcome}. The {@code connectTimeoutMillis} deadline ({@code --connect-timeout-millis}, the
     * timeout text prints it); silence ends in the timeout — this client has no version-12 HELLO to fall back to.
     * Main thread, BEFORE the reader starts, holding no lock (the
     * flight's caller law: its X25519 never runs under a session lock). The flight is {@code abandon()}ed in a
     * {@code finally} — the admit tail, REJECTED, the timeout, a send failure and the session-0
     * protocol break all end it, so its secret copy and every kept root are zeroed before this method leaves; a late
     * WELCOME13 after the admit is the reader's {@code welcome_duplicates}.
     *
     */
    private void handshake13() throws IOException {
        // the drill's HELLO MagicLib report bits (0 unless --hello-flags; probe flights keep FLAG_PROBE below)
        Handshake13Flight flight = newFlight(options.helloFlags(), options.helloVariant());
        try {
            long deadline = nowMillis() + options.connectTimeoutMillis();
            socket.setSoTimeout(HELLO_RETRY_MILLIS);
            byte[] open = flight.start(nowMillis());
            socket.send(new DatagramPacket(open, open.length, server));
            byte[] buf = new byte[RECEIVE_BUFFER_BYTES];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (nowMillis() < deadline) {
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException retry) {
                    // null only once the flight is ADMITTED or ended — both leave this loop below (return / throw)
                    byte[] again = flight.retransmitOrNull();
                    if (again != null) {
                        helloRetransmits++;
                        socket.send(new DatagramPacket(again, again.length, server));
                    }
                    continue;
                }
                DatagramFrameCodec.Frame frame =
                        DatagramFrameCodec.decode(packet.getData(), packet.getLength());
                if (frame == null || frame.channel() != DatagramFrameCodec.WireChannel.WELCOME
                        || !packet.getSocketAddress().equals(server)) {
                    droppedUndecodable++;
                    continue;
                }
                Handshake13Flight.Event event = flight.onWelcome(frame.payload(), nowMillis());
                switch (event.kind()) {
                    case SEND -> {
                        cookieChallenges++;
                        byte[] sealed = event.datagram();
                        socket.send(new DatagramPacket(sealed, sealed.length, server));
                    }
                    case HELD -> {
                        // the budget kept this pair unsealed, or the flight is at its seal cap: the resend keeps what
                        // the flight already sent
                    }
                    case IGNORE -> droppedUndecodable++;
                    case REJECTED -> throw new IOException("pilot datagram admission rejected: "
                            + rejectReasonOf(event.rejected()));
                    case ADMITTED -> {
                        adoptAdmittedWelcome(event.admitted().welcome());
                        return;
                    }
                    default -> throw new IllegalStateException("unhandled hs-13 event " + event.kind());
                }
            }
            throw new IOException("pilot datagram HELLO timed out after " + options.connectTimeoutMillis()
                    + " ms (" + helloRetransmits + " retransmits, no nonce-matching WELCOME)");
        } finally {
            flight.abandon(); // idempotent, legal in every state, never throws (Handshake13Flight#abandon)
        }
    }

    /**
     * One hs-13 flight on {@code --lane} with {@code --seat-token} as its secret ({@link Handshake13#secretBytes}; the
     * local byte copy is zeroed here; the flight's own copy is zeroed at its first ADMITTED, a REJECTED or
     * {@code abandon()}, which every caller runs in a {@code finally}), budgeted on the process's
     * per-host {@link Handshake13PairBudget} keyed by {@link Handshake13PairBudget#hostKey} — the TYPED {@code --host}
     * + port, the kit senders' key law, never the resolved address. The flight's constructor
     * refuses a print / cid / variant outside the codec's A-string caps: re-thrown naming the FIELD only (never its
     * value, never the secret), so the run fails at start-up before a datagram leaves — never a silent truncate.
     *
     */
    private Handshake13Flight newFlight(int flags, String variantId) {
        byte[] secret = Handshake13.secretBytes(options.seatToken());
        try {
            // a probe never carries a team pick; the session HELLO carries --team
            return new Handshake13Flight(options.lane(), secret, flags, options.fingerprint(),
                    options.clientId(), options.shipId(), variantId,
                    (flags & Handshake13.FLAG_PROBE) != 0 ? 0 : options.team(),
                    Handshake13PairBudget.process().host(
                            Handshake13PairBudget.hostKey(server.getHostString(), server.getPort())),
                    new SecureRandom());
        } catch (IllegalArgumentException refused) {
            throw new IllegalArgumentException("--hs13 flight refused field '" + refused.getMessage() + "' (an"
                    + " A-string of 0x21..0x7E within the codec cap: print " + Handshake13.PRINT_MAX + ", cid "
                    + Handshake13.CID_MAX + ", variant " + Handshake13.VARIANT_MAX + ")", refused);
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    /** A REJECTED WELCOME13's reason as the join and the probe print it ({@code (no reason)} for an empty one). */
    private static String rejectReasonOf(HandshakeCodec.WelcomePayload rejected) {
        return rejected.reason().isEmpty() ? "(no reason)" : rejected.reason();
    }

    /** The receipt's {@code hs=} row: the handshake version this run spoke — always
     * {@link Handshake13#VERSION} since THE FLIP (the drill client holds no other). */
    private String hsReceiptRow() {
        return "hs=" + Handshake13.VERSION;
    }

    /** The clock-driven UP loop: one PINT per tick at {@code --intent-hz} on the SNAP channel
     * (latest-wins), PDCL every {@code --decl-every} sent frames on CTRL, drill behaviors between. */
    private void streamIntents() throws IOException, InterruptedException {
        long periodNanos = 1_000_000_000L / options.intentHz();
        long nextAtNanos = System.nanoTime();
        boolean pauseTriggered = false;
        long nextStopPollMillis = 0L;
        long nextChatPollMillis = 0L;
        while (framesSent < options.maxFrames()) {
            checkTerminal();
            if (peerSaidBye) {
                return; // server-initiated goodbye — the reader already echoed its BYE-ACK
            }
            if (options.stopFile() != null && nowMillis() >= nextStopPollMillis) {
                nextStopPollMillis = nowMillis() + STOP_FILE_POLL_MILLIS;
                if (Files.exists(options.stopFile())) {
                    // The harness's graceful stop — end the stream here so run()
                    // reaches goodbye() and main() writes the receipt (a pid kill never does).
                    stopFileSeen = true;
                    System.out.println("pilot-datagram-client STOP-FILE seen frames_sent="
                            + framesSent + " (graceful goodbye)");
                    return;
                }
            }
            if (options.chatFile() != null && nowMillis() >= nextChatPollMillis) {
                nextChatPollMillis = nowMillis() + STOP_FILE_POLL_MILLIS;
                pollChatFile();
            }
            if (options.pauseAfterFrames() > 0 && !pauseTriggered
                    && framesSent >= options.pauseAfterFrames()) {
                pauseTriggered = true;
                pauseUntilMillis = nowMillis() + options.pauseMillis();
                System.out.println("pilot-datagram-client PAUSE frames_sent=" + framesSent
                        + " millis=" + options.pauseMillis() + " (outbound silence drill)");
            }
            if (nowMillis() >= pauseUntilMillis) {
                sendIntentFrame();
                if (options.hardExitAfter() > 0 && framesSent >= options.hardExitAfter()) {
                    // The crash leg: no BYE, no receipt, no socket close — indistinguishable
                    // from a process kill. halt() skips shutdown hooks on purpose.
                    System.err.println("pilot-datagram-client HARD-EXIT frames_sent=" + framesSent
                            + " (drill crash simulation)");
                    Runtime.getRuntime().halt(9);
                }
            }
            nextAtNanos += periodNanos;
            long sleepNanos = nextAtNanos - System.nanoTime();
            if (sleepNanos > 0L) {
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            } else {
                nextAtNanos = System.nanoTime(); // fell behind (pause/GC) — rebase, don't burst
            }
        }
    }

    /**
     * {@code --chat-file}: one poll of the harness-fed chat lane. A file with text
     * = up to {@link #CHAT_FILE_MAX_LINES} chat lines, sent in order on the reliable CTRL lane,
     * the file DELETED first (consume-on-read: a drop is sent at most once, and a delete that
     * fails sends nothing so it can never re-send every poll). An empty read is left alone — the
     * harness's create-then-write window. Fail-soft: a rig helper never kills the seat.
     */
    private void pollChatFile() throws IOException {
        Path file = options.chatFile();
        List<String> lines = new ArrayList<>();
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            for (String raw : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (!line.isEmpty() && lines.size() < CHAT_FILE_MAX_LINES) {
                    lines.add(line);
                }
            }
            if (lines.isEmpty()) {
                return;
            }
            Files.delete(file);
        } catch (IOException | RuntimeException unreadable) {
            return; // mid-write or locked: the next poll retries
        }
        // The send is fail-soft PER LINE. A blank / over-long /
        // law-breaking line must never throw (ChatFrameCodec.encodeUp validates and REJECTS such
        // text) out of the intent loop and kill the rig seat. ChatFrameCodec REJECTS over-long text
        // rather than truncating it, so match that contract: skip an over-long line, never mangle
        // the player's words. Chat text is DATA -- never logged raw; only counts are printed.
        //
        int sent = 0;
        int skipped = 0;
        for (String line : lines) {
            if (line.length() > ChatFrameCodec.TEXT_MAX_CHARS) {
                skipped++;
                continue;
            }
            try {
                ctrlSendBounded(ChatFrameCodec.encodeUp(line), "chat file");
                chatFileLinesSent++;
                sent++;
            } catch (IOException | RuntimeException bad) {
                skipped++; // one bad line (byte cap / non-printable / transport stall) is skipped
            }
        }
        System.out.println("pilot-datagram-client CHAT-FILE sent lines=" + sent
                + " skipped=" + skipped + " frames_sent=" + framesSent);
    }

    private void sendIntentFrame() throws IOException {
        AimView view = aimView;
        // frameSeq echoes the latest server simTick (the TCP client's decode-match semantics);
        // before the first AWSS it streams frameSeq 0 / aim 0 — sending from admission onward
        // settles the server's join grace and keeps the PDCL freshness bound honest (a synthetic
        // clock running ahead of server ticks would trip the stale gate).
        if (!deferredChatSent && options.chatAfterSent() > 0
                && framesSent >= options.chatAfterSent()) {
            deferredChatSent = true;
            sendChatLines();
        }
        int frameSeq = view == null ? 0 : view.simTick();
        float aimDeg = view == null ? 0f : view.aimDeg();
        int heldControls = (frameSeq <= options.strafeBurst() && options.strafeBurst() > 0)
                ? PilotIntentFrame.MOVE_STRAFE_LEFT : 0;
        // Held pursuit (aim pins onto the foe, so held accel closes on it) — for rigs whose
        // match shape spawns the seats outside weapon range.
        if (options.accelFrom() > 0 && frameSeq >= options.accelFrom()) {
            heldControls |= PilotIntentFrame.MOVE_ACCELERATE;
        }
        // The idle-posture brake — hold DECELERATE (the faithful S-key stop) while
        // the last observed own speed is above the threshold, neutral once parked. Stateless on
        // purpose: fly-in coast brakes at round start, and a knock that imparts drift re-arms it.
        if (options.brakeBelow() > 0f && ownLastSpeed > options.brakeBelow()) {
            heldControls |= PilotIntentFrame.MOVE_DECELERATE;
            brakeFrames++;
        }
        // Edge bits hold from their start frame ONWARD (unbounded hold — satisfies the frame-hold rule
        // >= EDGE_BIT_MIN_HOLD_FRAMES by construction; see the class doc).
        if (options.ventFrom() > 0 && frameSeq >= options.ventFrom()) {
            heldControls |= PilotIntentFrame.VENT_FLUX;
        }
        if (options.systemFrom() > 0 && frameSeq >= options.systemFrom()) {
            heldControls |= PilotIntentFrame.USE_SHIP_SYSTEM;
        }
        // Mod-ability slot 0, held LEVEL from its start frame onward: the accepted-state bit a kit sends while the
        // mod's slot-0 subsystem is IN|ACTIVE (0 = never, so every other drill arm sends no bit 13).
        //
        if (options.abilityFrom() > 0 && frameSeq >= options.abilityFrom()) {
            heldControls |= PilotIntentFrame.ABILITY_SLOT_0;
        }
        // --ability-window slot:from:to — the slot's ability bit held over [from, to): the seam drill's press (row 19)
        // and the cleared bit's cancel (row 21) ride this one window.
        int[] window = options.abilityWindow();
        if (window != null && frameSeq >= window[1] && frameSeq < window[2]) {
            heldControls |= PilotIntentFrame.ABILITY_SLOT_0 << window[0];
            abilityWindowHeld++;
        }
        // The cloak state-mirror bit, held level (the server's applyCloakBit
        // toggles on mismatch, so an engine-forced OUT — overload, vent — re-raises by itself).
        if (options.cloakFrom() > 0 && frameSeq >= options.cloakFrom()) {
            heldControls |= PilotIntentFrame.PHASE_CLOAK;
        }
        // The shield state-mirror bit, held level (same toggle-on-mismatch law
        // as the cloak — an engine-forced OUT re-raises by itself). The arena decoy holds it up.
        if (options.shieldFrom() > 0 && frameSeq >= options.shieldFrom()) {
            heldControls |= PilotIntentFrame.SHIELD_ON;
        }
        boolean fireHeld = fireHeldAt(frameSeq);
        // Count the press's rising edge into the wrapping 0..255 fireEdgeSeq — the
        // real producer's exact law (PredictReconcileCombatPlugin fire sample), so the server's
        // tap-integrity latch (ECB observeFireEdge) sees faithful press/release cycles. The
        // degenerate always-held default (fire-from 0) edges ONCE on the first frame; the server
        // baselines its first observed seq, so that lane stays latch-inert as before.
        if (fireHeld && !fireEdgePrevHeld) {
            fireEdgeSeq = (fireEdgeSeq + 1) & 0xFF;
            fireRisingEdges++;
        }
        fireEdgePrevHeld = fireHeld;
        PilotIntentFrame intent = new PilotIntentFrame(options.shipId(), frameSeq, aimDeg,
                heldControls, fireHeld, options.selectedGroup(), aimDeg, 0f, 0f,
                options.autofireMask(), PilotIntentFrame.TARGET_ABSENT, fireEdgeSeq,
                PilotIntentFrame.TUBE_ORDINAL_ABSENT);
        ByteArrayOutputStream frameBytes = new ByteArrayOutputStream(PilotIntentWireCodec.FRAME_BYTES);
        PilotIntentWireCodec.writeFrame(frameBytes, intent);
        byte[] bytes = frameBytes.toByteArray();
        // Digest exactly the payload bytes handed to the wire (envelope excluded — the server
        // records UP payloads, so byte-identity is provable payload-to-payload). Under loss the
        // digest covers SENT frames; arrival accounting lives in the counters.
        digest.update(bytes);
        requireReliability().snapSend(bytes, nowMillis());
        framesSent++;
        lastAimDeg = aimDeg;
        // PDCL cadence is frames-SENT based (not the TCP client's frameSeq modulo — frameSeq
        // repeats between server ticks here), so decl_sent == floor(frames_sent / declEvery) is a
        // deterministic smoke assertion. clientTick echoes frameSeq for the freshness gate.
        if (options.declEvery() > 0 && framesSent % options.declEvery() == 0) {
            byte[] decl = FireDischargeDeclarationCodec.encodeFrame(FireDischargeDeclaration.of(
                    options.shipId(), declSeq++, frameSeq, aimDeg, 0f, 0f));
            ctrlSendBounded(decl, "fire-discharge declaration");
            declSent++;
        }
        // Drain reader-deposited AWSD NACKs on the pump thread (deposit/drain law —
        // see the field doc; sending here rides the same bounded CTRL path as declarations).
        for (AwsdNackFrameCodec.AwsdNack nack : merger.drainPendingNacks()) {
            ctrlSendBounded(AwsdNackFrameCodec.encodeFrame(nack.entityId(), nack.epoch()),
                    "awsd nack");
            snapAwsdNackTx++;
        }
    }

    /** Fire schedule: windows win when given (held while from <= frameSeq < to —
     * `to` is the release tick); otherwise the legacy unbounded --fire-from hold. frameSeq echoes
     * the server simTick and repeats between server ticks, so a window holds across repeats and
     * both edges land on exact server ticks. */
    private boolean fireHeldAt(int frameSeq) {
        int[] windows = options.fireWindows();
        if (windows.length == 0) {
            return frameSeq >= options.fireFrom();
        }
        for (int i = 0; i < windows.length; i += 2) {
            if (frameSeq >= windows[i] && frameSeq < windows[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** Goodbye: bounded CTRL flush, then BYE resent by the timer until the peer's BYE echo.
     * Returns clean = echo received (or the peer said goodbye first — already echoed by the reader).
     * The flush runs on BOTH paths: a server-initiated BYE does not relieve our unacked CTRL
     * (PDCL declarations) — TCP's kernel flushes the ordered stream before FIN; the datagram
     * sibling must app-flush or in-flight-lost declarations die with the process. */
    private boolean goodbye() throws IOException {
        DatagramReliability rel = reliability;
        long deadline = nowMillis() + GOODBYE_BOUND_MILLIS;
        while (rel != null && !rel.ctrlFlushed() && nowMillis() < deadline
                && sessionFailure == null && readerFailure == null) {
            sleepQuietly(TIMER_GRANULARITY_MILLIS);
        }
        if (peerSaidBye) {
            return true;
        }
        byeSent = true;
        sendEnvelopeOnly(DatagramFrameCodec.WireChannel.BYE);
        while (!byeAcked && !peerSaidBye && nowMillis() < deadline && readerFailure == null
                && sessionFailure == null) {
            sleepQuietly(TIMER_GRANULARITY_MILLIS); // the timer thread resends the BYE
        }
        return byeAcked || peerSaidBye;
    }

    private synchronized String sentSha256() {
        if (sentSha256Hex == null) {
            sentSha256Hex = toHex(digest.digest());
        }
        return sentSha256Hex;
    }

    private void checkTerminal() throws IOException {
        IOException failure = sessionFailure;
        if (failure != null) {
            throw failure;
        }
        failure = readerFailure;
        if (failure != null) {
            throw failure;
        }
        long silent = nowMillis() - lastValidArrivalMillis;
        if (silent >= options.readDeadlineMillis()) {
            // The client-side silence death (server kicked us / died / path black): same reason
            // token as the server's grammar so drill receipts grep one vocabulary.
            throw new IOException("pilot datagram client read deadline exceeded ("
                    + options.readDeadlineMillis() + " ms of server datagram silence; reason"
                    + " token: udp_silence — " + silent + " ms silent)");
        }
    }

    // ------------------------------------------------------------------ reader thread

    private void readLoop() {
        byte[] buf = new byte[RECEIVE_BUFFER_BYTES];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                socket.receive(packet);
            } catch (IOException receiveFailed) {
                if (!closed) {
                    readerFailure = new IOException(
                            "pilot datagram client receive failed", receiveFailed);
                }
                return;
            }
            DatagramFrameCodec.Frame frame =
                    DatagramFrameCodec.decode(packet.getData(), packet.getLength());
            if (frame == null) {
                droppedUndecodable++;
                continue;
            }
            SocketAddress source = packet.getSocketAddress();
            if (frame.channel() == DatagramFrameCodec.WireChannel.WELCOME) {
                // A handshake-retransmit crossing its WELCOME in flight resolves here
                // (idempotency): the server re-answered our duplicate HELLO. Benign.
                welcomeDuplicates++;
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
            if (frame.sessionId() != sessionId) {
                droppedBadSession++;
                continue;
            }
            lastValidArrivalMillis = nowMillis();
            if (frame.channel() == DatagramFrameCodec.WireChannel.BYE) {
                handleBye();
                continue;
            }
            DatagramReliability.Channel channel = switch (frame.channel()) {
                case SNAP -> DatagramReliability.Channel.SNAP;
                case CTRL -> DatagramReliability.Channel.CTRL;
                case ACK -> DatagramReliability.Channel.ACK;
                default -> null; // unreachable: HELLO/WELCOME/BYE handled above
            };
            if (channel != null) {
                DatagramReliability rel = reliability;
                if (rel != null) {
                    // Stash the envelope roundId for the synchronous delivery callbacks (why:
                    // the core is roundId-blind; callbacks run on this thread inside onDatagram).
                    readerRoundId = frame.roundId();
                    rel.onDatagram(channel, frame.seq(), frame.ctrlAck(), frame.ctrlAckBits(),
                            frame.snapHiSeen(), nowMillis(),
                            frame.payload().length == 0 ? null : frame.payload());
                }
            }
        }
    }

    /** Envelope roundId of the datagram currently being fed to the core — reader thread only,
     * valid inside the synchronous delivery callbacks (application gating). */
    private int readerRoundId;

    private void handleBye() {
        if (byeSent && !byeAcked) {
            byeAcked = true; // BYE-ACK = BYE echo: the peer's BYE completes our goodbye
        }
        if (!peerSaidBye) {
            peerSaidBye = true;
            sendEnvelopeOnly(DatagramFrameCodec.WireChannel.BYE); // our echo = the peer's BYE-ACK
        }
    }

    // ------------------------------------------------------------------ reliability callbacks
    // (reader thread, under the core's monitor; may take stateLock — never the reverse)

    /** DOWN CTRL routing: GPEE / EDSP / MRST (round flip) / SNMS / SEAT (N-serve seat
     * state) / HKPS+HKFR+HKCG (hulk lanes — decoded and counted; the
     * first foe death killed the wave-rig ghost witness before these branches
     * existed) / RAPP (roster append — decoded, rows + ids recorded for the
     * wave-rig receipt rows) / MOVR (serve-end match verdict — decoded +
     * deposited for {@link #matchOverRecords()} and the receipt rows) / PSTB (the
     * once-per-admission posture block — decoded + counted); reserved DESC/HOUS
     * kinds are TOLERATED — count-ignored, mirroring the
     * client mod's own tolerate branch (a future consumer
     * arc must never kill a synthetic probe run); anything else on a CRC-verified
     * post-admission channel is a protocol break — fail the run loud. */
    private void onCtrlDelivered(long seq, byte[] payload) {
        int magic = payloadMagic(payload);
        try {
            if (magic == GameplayEdgeEventCodec.MAGIC) {
                GameplayEdgeEventCodec.readEvent(new ByteArrayInputStream(payload));
                gpeeReceived++;
            } else if (magic == EntityDespawnEventCodec.MAGIC) {
                EntityDespawnEvent despawn =
                        EntityDespawnEventCodec.readEvent(new ByteArrayInputStream(payload));
                // Under merge, store EVICTION is the death edge — the server
                // spools CTRL before the tick's SNAP chunks, so the evict precedes the drop frame.
                merger.evict(despawn.entityId());
                edspReceived++;
            } else if (magic == SeatStateFrameCodec.SEAT_STATE_MAGIC) {
                SeatStateFrameCodec.decodeSeatState(payload);
                seatStateReceived++;
            } else if (magic == MatchResetFrameCodec.MAGIC) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
                in.readInt(); // magic
                MatchResetFrameCodec.readFrameAfterMagic(in);
                onMatchReset();
            } else if (magic == SeatNameFrameCodec.SEAT_NAMES_MAGIC) {
                SeatNameFrameCodec.SeatNames names =
                        SeatNameFrameCodec.readSeatNames(new ByteArrayInputStream(payload));
                if (seatNames == null) {
                    seatNames = names;
                }
            } else if (magic == SeatNameFrameCodec.SEAT_NAME_RECORD_MAGIC) {
                // One N-aware per-seat SNMR record (announce fan or admission
                // catch-up) — latest per seat wins (a takeover re-announce replaces).
                SeatNameFrameCodec.SeatNameRecord record =
                        SeatNameFrameCodec.readSeatNameRecord(new ByteArrayInputStream(payload));
                seatNameRecords.put(record.seatId(), record);
                seatNameRecordsReceived++;
            } else if (magic == ReservedControlFrameCodec.MAGIC_DESCRIPTOR
                    || magic == ReservedControlFrameCodec.MAGIC_HOUSE) {
                // Tolerated, never decoded — the datagram boundary means
                // there is no stream sync to protect, and the envelope CRC already excluded
                // corruption; the counter is the observable surface (mod-lane mirror).
                reservedCtrlIgnored++;
            } else if (magic == ChatFrameCodec.DOWN_MAGIC) {
                // One relayed chat line, server-stamped attribution self-contained.
                ChatFrameCodec.ChatDown chat = ChatFrameCodec.decodeDown(payload);
                lastChat = chat.seatId() + "|" + chat.name() + "|" + chat.text();
                chatReceived++;
            } else if (magic == HulkPoseFrameCodec.POSE_MAGIC) {
                HulkPoseFrameCodec.read(new ByteArrayInputStream(payload));
                hkpsReceived++;
            } else if (magic == HulkFractureFrameCodec.FRACTURE_MAGIC) {
                HulkFractureFrameCodec.read(new ByteArrayInputStream(payload));
                hkfrReceived++;
            } else if (magic == HulkCutGeometryFrameCodec.CUT_GEOMETRY_MAGIC) {
                HulkCutGeometryFrameCodec.read(new ByteArrayInputStream(payload));
                hkcgReceived++;
            } else if (magic == RosterAppendFrameCodec.MAGIC) {
                // Decoded + recorded — the wave_spawn RAPP fan (live or
                // late-joiner catch-up) would otherwise kill the wave-rig ghost witness at the
                // bad-magic throw below (the HKPS first-foe-death precedent one branch up).
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
                in.readInt(); // magic
                for (RosterAppendFrameCodec.AppendRow row
                        : RosterAppendFrameCodec.readFrameAfterMagic(in)) {
                    rappWireIds.add(row.wireId());
                    rappRowsReceived++;
                }
                rappReceived++;
            } else if (magic == MatchOverFrameCodec.MATCH_OVER_MAGIC) {
                // The serve-end MOVR verdict rides CTRL ahead of the goodbye
                // BYE, so every matchOver-ending serve would otherwise die on the throw below
                // (the RAPP/HKFR precedent: parse-and-expose, never fail a synthetic run).
                matchOverRecords.add(MatchOverFrameCodec.decode(payload));
                movrReceived++;
            } else if (magic == PostureFrameCodec.BLOCK_MAGIC) {
                // The once-per-admission PSTB posture block — UNCONDITIONAL
                // at the N door, so every admitted synthetic client sees it as its first down
                // CTRL (the MOVR precedent: parse-and-expose, never fail a synthetic run). The
                // synthetic client has no posture to re-key; the tag is a receipt row.
                PostureFrameCodec.PostureBlock block = PostureFrameCodec.decodeBlock(payload);
                // The codec's no-tag sentinel is "" (never null) — receipt rows say "none".
                pstbLastTag = block.experimentTag().isEmpty() ? "none" : block.experimentTag();
                pstbReceived++;
            } else if (magic == cc.starsector.server.runtime.VoiceConfigFrameCodec.MAGIC) {
                // W12 voice L2: the once-per-admission (and per-retune) VCFG
                // voice sheet — decoded fail-closed + counted; the synthetic client has no
                // codec to tune (the PSTB precedent: parse-and-expose, never fail a run).
                cc.starsector.server.runtime.VoiceConfigFrameCodec.decode(payload);
                vcfgReceived++;
            } else if (magic == cc.starsector.server.runtime.ListingFrameCodec.DOWN_MAGIC) {
                // The post-admission LSTD drip (/debugships full catalogue —
                // the first >64-frame listing regime). KIND_DEBUGSHIP collected latest-
                // listingId-wins mirroring the mod reader; other kinds tolerated + counted
                // (the reserved DESC/HOUS precedent: never kill a synthetic run). Malformed
                // payload falls to the corrupt catch below — on the CRC-verified reliable
                // lane that IS a protocol break.
                cc.starsector.server.runtime.ListingFrameCodec.ListingDown down =
                        cc.starsector.server.runtime.ListingFrameCodec.decodeDown(payload);
                if (down.kind() == cc.starsector.server.runtime.ListingFrameCodec.KIND_DEBUGSHIP) {
                    if (down.listingId() > listingLatestId) {
                        listingLatestId = down.listingId();
                        listingFrames.clear();
                    }
                    if (down.listingId() == listingLatestId) {
                        listingFrames.put(down.seqIndex(), down);
                    } else {
                        listingStaleDropped++;
                    }
                } else {
                    listingOtherIgnored++;
                }
            } else if (magic == cc.starsector.server.runtime.VariantDefinitionFrameCodec.MAGIC) {
                // The W7c B2 VDEF variant-catalog burst the PRODUCT (ops) serve
                // spools to every newcomer at admission (PilotIntentDatagramServer VDEF spool;
                // VariantCatalogControlMessage) — decoded fail-closed + counted; the synthetic
                // client has no engine registry to fold into (the VCFG/LSTD precedent:
                // parse-and-expose, never fail a synthetic run). Without this arm a synthetic
                // seat died at admission on the public lane (a recorded drop-selftest run:
                // "CTRL bad magic: 0x56444546") — the rig lanes never armed the catalog.
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
                in.readInt(); // magic (peeked by the demux; the codec reads AFTER it)
                // No id table on the synthetic seat — the envelope + CRC are validated and
                // the client-local variantId (a wire string) is peeked off the raw body.
                byte[] vdefBody = cc.starsector.server.runtime.VariantDefinitionFrameCodec.readRawBodyAfterMagic(in);
                vdefReceived++;
                String vdefId = cc.starsector.server.runtime.VariantDefinitionFrameCodec.peekVariantId(vdefBody);
                if (vdefId.startsWith("cc-v")) {
                    vdefCustomIds.add(vdefId); // W15 v0: the runtime custom fan reached us
                }
            } else if (magic == cc.starsector.server.runtime.VariantReceiptFrameCodec.MAGIC) {
                // W15 v0: the VACK unicast answering OUR --vsub-file submission(s) —
                // decoded fail-closed (the corrupt catch below), logged verbatim for the receipt.
                // MUST sit before the DownFrameFamily arm (VACK is a family member).
                cc.starsector.server.runtime.VariantReceiptFrameCodec.VariantReceipt vack =
                        cc.starsector.server.runtime.VariantReceiptFrameCodec.decodePayload(payload);
                if (vack.accepted()) {
                    vackAccepted++;
                } else {
                    vackRejected++;
                }
                vackLog.add(vack.submitSeq() + ":" + (vack.accepted() ? "accepted" : "rejected")
                        + ":" + vack.reasonToken() + ":" + vack.variantId());
            } else if (magic == cc.starsector.server.runtime.PilotAimFrameCodec.MAGIC) {
                // the AIMP piloted-seat aim-point fan (PilotAimFrameCodec,
                // DOWN-only, fixed-width, no UTF) reaches a synthetic seat once ANOTHER piloted seat
                // exists — the kit seat beside seat 0 on the drop self-test (one recorded run:
                // "CTRL bad magic: 0x41494d50" AFTER the VDEF burst decoded, vdef_received=22). The
                // codec has no reader (the client mod owns the decode); tolerated + counted like the
                // reserved DESC/HOUS kinds, with the codec's own frame-size bound as the only check.
                if (payload.length > cc.starsector.server.runtime.PilotAimFrameCodec.MAX_FRAME_BYTES) {
                    throw new IOException("AIMP frame oversize: " + payload.length + " B > "
                            + cc.starsector.server.runtime.PilotAimFrameCodec.MAX_FRAME_BYTES);
                }
                aimpReceived++;
            } else if (cc.starsector.server.runtime.DownFrameFamily.contains(magic)) {
                // Every remaining DOWN-family kind (XFER transfer fan, MMNT
                // module mount + newcomer replay, MRER, CBTO, DIVE, VCDN voice, PWVW, …) is
                // advanced by its OWN codec read through the single-sourced family walk —
                // fail-closed on a malformed frame (the corrupt catch below), tolerated + counted
                // per leading magic when whole. Four separate runs met these one at a time (VDEF and
                // AIMP among them: each a seat death at "CTRL bad magic"); a kind the serve can
                // put on the wire is a family member by law (ReservedCtrlGoldenContract pins the
                // list), so the synthetic seat now survives whatever a real match emits. A magic
                // OUTSIDE the family still dies loudly below — unknown stays unknown.
                cc.starsector.server.runtime.DownFrameFamily.demux(payload, java.util.Map.of());
                familyTolerated.merge(magic, 1L, Long::sum);
            } else {
                sessionFailure = new IOException("pilot datagram client CTRL bad magic: 0x"
                        + Integer.toHexString(magic)
                        + " (expected GPEE, EDSP, SEAT, MRST, SNMS seat names, SNMR name record,"
                        + " CHTD chat, HKPS/HKFR/HKCG hulk frames, RAPP roster append,"
                        + " MOVR match verdict, PSTB posture block, VCFG voice sheet,"
                        + " LSTD listing, VDEF variant definition, AIMP aim fan,"
                        + " a tolerated reserved DESC/HOUS kind, or any DownFrameFamily kind)");
            }
        } catch (IOException corrupt) {
            sessionFailure = new IOException(
                    "pilot datagram client CTRL payload corrupt (seq " + seq + "): " + corrupt,
                    corrupt);
        }
    }

    /** Round flip: exactly one increment per CTRL-delivered MRST (dedup is the core's), never
     * backwards; a buffered next-round snapshot applies immediately on the flip. */
    private void onMatchReset() {
        mrstReceived++;
        currentRoundId = (currentRoundId + 1) & 0xFF;
        merger.clear(); // Round boundary — before any buffered snap applies
        byte[] buffered;
        synchronized (stateLock) {
            if (bufferedFutureSnap == null || bufferedFutureRound != currentRoundId) {
                return;
            }
            buffered = bufferedFutureSnap;
            bufferedFutureSnap = null;
        }
        snapFutureApplied++;
        applySnapshot(buffered);
    }

    /** Drain the MOVR verdicts delivered so far (reader thread deposits; the
     * C6 leg-(b) consumer polls between frames). The receipt rows PEEK instead — building a
     * receipt never consumes the intake. */
    public List<MatchOverFrameCodec.MatchOverRecord> matchOverRecords() {
        List<MatchOverFrameCodec.MatchOverRecord> drained = new ArrayList<>();
        MatchOverFrameCodec.MatchOverRecord record;
        while ((record = matchOverRecords.poll()) != null) {
            drained.add(record);
        }
        return drained;
    }

    /** DOWN SNAP: AWSS, AWSD (the delta lane) or their CAWZ deflate wrap
     * only, gated by the round barrier before application. The buffered-future slot stashes
     * the payload UNINFLATED — the single inflate site is {@link #applySnapshot} entry, so a
     * stale-dropped or overwritten future payload costs zero inflate work; family dispatch
     * happens on the post-inflate magic there. */
    private void onSnapDelivered(long seq, byte[] payload) {
        int magic = payloadMagic(payload);
        if (magic != AuthoritativeWorldSnapshotCodec.MAGIC && magic != SnapDeflateCodec.MAGIC
                && magic != AwsdSnapshotCodec.MAGIC) {
            sessionFailure = new IOException("pilot datagram client SNAP bad magic: 0x"
                    + Integer.toHexString(magic) + " (expected AWSS, CAWZ, or AWSD)");
            return;
        }
        int frameRound = readerRoundId;
        int current = currentRoundId;
        if (frameRound == current) {
            applySnapshot(payload);
            return;
        }
        if (frameRound == ((current + 1) & 0xFF)) {
            // Next-round SNAP racing its (retransmitted) MRST: buffer one slot, latest-wins —
            // dropping would black out round N+1 for up to one RTO on lossy legs.
            synchronized (stateLock) {
                bufferedFutureSnap = payload;
                bufferedFutureRound = frameRound;
            }
            snapFutureBuffered++;
            return;
        }
        snapStaleRoundDropped++;
    }

    private void applySnapshot(byte[] payload) {
        byte[] raw;
        try {
            // Single inflate site: a CAWZ payload inflates back to the raw AWSS
            // frame; a raw-fallback (or pre-L1) payload passes through unchanged. The monitor
            // orders this against close()'s end() (a torn-down inflater must never race a
            // late reader-thread apply).
            synchronized (snapInflater) {
                if (closed) {
                    return; // teardown won the race — the inflater may already be ended
                }
                raw = SnapDeflateCodec.unwrapIfWrapped(snapInflater, payload);
            }
        } catch (IOException corrupt) {
            sessionFailure = new IOException(
                    "pilot datagram client CAWZ payload corrupt: " + corrupt, corrupt);
            return;
        }
        int magic = payloadMagic(raw);
        if (magic == AwsdSnapshotCodec.MAGIC) {
            // The delta lane — same round barrier/buffer upstream, family
            // dispatch on the post-inflate magic.
            applyAwsd(raw);
            return;
        }
        if (magic != AuthoritativeWorldSnapshotCodec.MAGIC) {
            // The onSnapDelivered gate re-checked post-inflate: what a wrapper carried must
            // itself be an AWSS or AWSD frame (the gate moved below the inflate).
            sessionFailure = new IOException("pilot datagram client SNAP bad magic post-inflate:"
                    + " 0x" + Integer.toHexString(magic) + " (expected AWSS or AWSD)");
            return;
        }
        List<AuthoritativeWorldSnapshot> decoded;
        try {
            decoded = AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(raw));
        } catch (IOException corrupt) {
            sessionFailure = new IOException(
                    "pilot datagram client AWSS payload corrupt: " + corrupt, corrupt);
            return;
        }
        if (decoded.isEmpty()) {
            return;
        }
        AuthoritativeWorldSnapshot snap = decoded.get(0);
        // per-(entity, tier) latest-wins reassembly, reader
        // thread only; the law lives in MirrorChunkMerger. Movement
        // observation stays keyed to the ARRIVING frame's own record so receipt semantics are
        // unchanged at K=1; aim runs over the published view (identity frame or merged store).
        AuthoritativeEntitySnapshot ownArrived = null;
        for (AuthoritativeEntitySnapshot entity : snap.entities()) {
            if (entity.entityId() == options.shipId()) {
                ownArrived = entity;
                break;
            }
        }
        MirrorChunkMerger.MergedView view = merger.onFrame(snap);
        publishView(view.entities(), view.viewTick(), ownArrived);
    }

    /** AWSD (delta+keyframe) SNAP application — decode here, fold rules in
     * {@link MirrorChunkMerger#onAwsdFrame}. Publish = the merge-branch
     * store synthesis (ship-empty AWSS heartbeats and AWSD frames share the same store). */
    private void applyAwsd(byte[] raw) {
        AwsdSnapshotCodec.AwsdFrame frame;
        try {
            frame = AwsdSnapshotCodec.readFrame(new ByteArrayInputStream(raw));
        } catch (IOException corrupt) {
            sessionFailure = new IOException(
                    "pilot datagram client AWSD payload corrupt: " + corrupt, corrupt);
            return;
        }
        MirrorChunkMerger.AwsdView out = merger.onAwsdFrame(frame, options.shipId());
        publishView(out.view().entities(), out.view().viewTick(), out.ownArrived());
    }

    /** Shared publish tail (identity, merge, and AWSD paths): own/foe extraction over the
     * published view, own-movement observation keyed to the ARRIVING frame's own record, aim. */
    private void publishView(List<AuthoritativeEntitySnapshot> view, long viewTick,
            AuthoritativeEntitySnapshot ownArrived) {
        AuthoritativeEntitySnapshot own = null;
        AuthoritativeEntitySnapshot foe = null;
        for (AuthoritativeEntitySnapshot entity : view) {
            if (entity.entityId() == options.shipId()) {
                own = entity;
            } else if (foe == null) {
                foe = entity;
            }
        }
        synchronized (stateLock) {
            if (ownArrived != null) {
                ownFramesObserved++;
                if (Float.isNaN(ownFirstX)) {
                    ownFirstX = ownArrived.locX();
                    ownFirstY = ownArrived.locY();
                }
                float disp = (float) Math.hypot(
                        ownArrived.locX() - ownFirstX, ownArrived.locY() - ownFirstY);
                if (disp > ownMaxDisp) {
                    ownMaxDisp = disp;
                }
                float speed = (float) Math.hypot(ownArrived.velX(), ownArrived.velY());
                if (speed > ownMaxSpeed) {
                    ownMaxSpeed = speed;
                }
                ownLastSpeed = speed;
            }
        }
        float aimDeg;
        AimView previous = aimView;
        if (own != null && foe != null) {
            float bearing = (float) Math.toDegrees(
                    Math.atan2(foe.locY() - own.locY(), foe.locX() - own.locX()));
            aimDeg = bearing < 0f ? bearing + 360f : bearing;
        } else {
            // Foe absent = the death-teardown frame (TCP-client posture): hold the last aim.
            aimDeg = previous == null ? 0f : previous.aimDeg();
        }
        aimView = new AimView((int) viewTick, aimDeg);
        snapFreshApplied++;
    }

    // ------------------------------------------------------------------ outbound

    /** The reliability core's sink: envelope (current round, adopted session) + send. Called under
     * the core monitor — a datagram send is the allowed blocking; failures are silent (silence
     * is the death signal, and racing close() is benign). */
    private void emitFromCore(DatagramReliability.Emission emission) {
        DatagramFrameCodec.WireChannel channel = switch (emission.channel()) {
            case SNAP -> DatagramFrameCodec.WireChannel.SNAP;
            case CTRL -> DatagramFrameCodec.WireChannel.CTRL;
            case ACK -> DatagramFrameCodec.WireChannel.ACK;
        };
        sendRawDatagram(DatagramFrameCodec.encode(channel, currentRoundId, sessionId,
                emission.seq(), emission.ctrlAck(), emission.ctrlAckBits(), emission.snapHiSeen(),
                emission.payload()));
    }

    private void sendEnvelopeOnly(DatagramFrameCodec.WireChannel channel) {
        long session = sessionId;
        if (session == 0L) {
            return;
        }
        sendRawDatagram(DatagramFrameCodec.encode(channel, currentRoundId, session, 0L, 0L, 0, 0L,
                null));
    }

    /** The ONE outbound funnel — the pause drill gates here so a paused client is wire-silent
     * across every lane (intents, acks, retransmits, keepalives, BYE echoes). */
    private void sendRawDatagram(byte[] datagram) {
        if (nowMillis() < pauseUntilMillis) {
            pausedOutboundDropped++;
            return;
        }
        try {
            socket.send(new DatagramPacket(datagram, datagram.length, server));
        } catch (IOException sendFailed) {
            // Not a death signal (silence is); racing close() is benign.
        }
    }

    /** Bounded-block-then-terminal CTRL send (the window-full policy, client side). */
    private void ctrlSendBounded(byte[] frame, String what) throws IOException {
        DatagramReliability rel = requireReliability();
        long now = nowMillis();
        if (rel.ctrlSend(frame, now)) {
            return;
        }
        long deadline = now + rel.currentRtoMillis();
        while (nowMillis() < deadline && sessionFailure == null && readerFailure == null
                && !closed) {
            sleepQuietly(TIMER_GRANULARITY_MILLIS);
            if (rel.ctrlSend(frame, nowMillis())) {
                return;
            }
        }
        throw new IOException("pilot datagram client CTRL window stalled writing " + what
                + " (reason token: ctrl_window_stall — window full past one RTO of "
                + rel.currentRtoMillis() + " ms; server not acking)");
    }

    // ------------------------------------------------------------------ timer thread

    private void timerLoop() {
        while (!closed) {
            try {
                Thread.sleep(TIMER_GRANULARITY_MILLIS);
            } catch (InterruptedException interrupted) {
                return; // close() interrupts — client lifetime over
            }
            DatagramReliability rel = reliability;
            if (rel != null) {
                rel.onTimer(nowMillis());
            }
            if (byeSent && !byeAcked && !peerSaidBye) {
                // Goodbye resend until the peer's BYE echo — the server-endpoint mirror.
                sendEnvelopeOnly(DatagramFrameCodec.WireChannel.BYE);
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle + receipt

    private void close() {
        closed = true;
        socket.close(); // unblocks the reader's receive()
        reader.interrupt();
        timer.interrupt();
        synchronized (snapInflater) {
            snapInflater.end(); // ordered against a mid-apply inflate (applySnapshot's monitor)
        }
    }

    private DatagramReliability requireReliability() throws IOException {
        DatagramReliability rel = reliability;
        if (rel == null) {
            throw new IOException("pilot datagram client is not admitted (handshake first)");
        }
        return rel;
    }

    /** Full counter snapshot for the receipt — populated on success AND failure paths, so drills
     * can assert the death class (error text + counters) from the receipt alone. */
    // ------------------------------------------------------------------ probe

    /**
     * The door PROBE twin of {@link #handshake13()} — the
     * game-free stand-in for the JoinGui's pre-launch list fetch. Sends {@code --probe-burst}
     * probe flights (credential = the match password, fingerprint as dialed) from the ONE socket, then collects the
     * door's RAW pre-admission {@code /ship} LSTD frames — channel CTRL, sessionId 0, seq =
     * frame index — from the dialed address until {@code min(seqCount, PROBE_MAX_FRAMES)}
     * distinct frames landed or {@code --read-deadline-millis} passed. A flight's REJECT
     * WELCOME13 throws with its readable reason (the same surface a join gets); an ADMITTED
     * WELCOME13 is a protocol break. NO retransmit: the door's guess ledger
     * grants each source key (IPv4 address, IPv6 /56) one verified HELLO per
     * {@code min(250 ms << failStreak, 8 s)} — a never-seen key also spends the 16/s global
     * first-seen window, a key at streak >= 3 the 8/s suspect window — and drops a held HELLO
     * silently by design, so the PS drill loops when it wants a retry.
     *
     * <p><b>Version 13 only</b>: the burst is {@code --probe-burst}
     * {@link Handshake13Flight}s with {@link Handshake13#FLAG_PROBE} and variant {@code ""},
     * ALL built before the first send (a refused field throws with no datagram out), then each one's F1 sent
     * back-to-back from the ONE socket; a WELCOME-channel payload goes to {@link #onProbeWelcome13}. No version-12 byte
     * exists to send: a silent door ends in the "no listing frame" text. A
     * burst past {@link Handshake13PairBudget#CAPACITY} is NOT held at an honest door: every flight in one 60 s cookie
     * bucket meets the same door share pair, and a pair the host remembers is free. A PASSING probe is answered by
     * plaintext CTRL listing frames only — no event ends its flights — so every flight is {@code abandon()}ed in this
     * method's {@code finally}, on every exit. Main thread (the probe path never starts the reader or the
     * timer), no lock held — the flight's own monitor is the only one.
     *
     */
    private void probe() throws IOException {
        List<Handshake13Flight> flights = new ArrayList<>();
        try {
            probeBurstAndCollect(flights);
        } finally {
            for (Handshake13Flight flight : flights) {
                flight.abandon(); // idempotent, never throws (Handshake13Flight#abandon)
            }
        }
    }

    /** {@link #probe()}'s body: the burst (the version-13 flights it fills into {@code flights}), then the listing
     * collector + drain. */
    private void probeBurstAndCollect(List<Handshake13Flight> flights) throws IOException {
        socket.setSoTimeout(HELLO_RETRY_MILLIS);
        for (int i = 0; i < options.probeBurst(); i++) {
            flights.add(newFlight(Handshake13.FLAG_PROBE, ""));
        }
        for (Handshake13Flight flight : flights) {
            byte[] open = flight.start(nowMillis());
            socket.send(new DatagramPacket(open, open.length, server));
        }
        long deadline = nowMillis() + options.readDeadlineMillis();
        byte[] buf = new byte[RECEIVE_BUFFER_BYTES];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        int expected = -1;
        while (nowMillis() < deadline && (expected < 0 || probeFrames.size() < expected)) {
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException idle) {
                continue;
            }
            if (!packet.getSocketAddress().equals(server)) {
                droppedUndecodable++;
                continue;
            }
            DatagramFrameCodec.Frame frame =
                    DatagramFrameCodec.decode(packet.getData(), packet.getLength());
            if (frame == null) {
                droppedUndecodable++;
                continue;
            }
            if (frame.channel() == DatagramFrameCodec.WireChannel.WELCOME) {
                onProbeWelcome13(flights, frame.payload());
                continue;
            }
            if (frame.channel() != DatagramFrameCodec.WireChannel.CTRL || frame.sessionId() != 0L) {
                droppedUndecodable++;
                continue;
            }
            cc.starsector.server.runtime.ListingFrameCodec.ListingDown listing;
            try {
                listing = cc.starsector.server.runtime.ListingFrameCodec.decodeDown(frame.payload());
            } catch (IOException malformed) {
                droppedUndecodable++;
                continue;
            }
            if (listing.kind() != cc.starsector.server.runtime.ListingFrameCodec.KIND_SHIP
                    || listing.seqIndex() != frame.seq()) {
                droppedUndecodable++;
                continue;
            }
            probeDatagrams++;
            probeFrames.put(listing.seqIndex(), listing);
            expected = Math.min(listing.seqCount(),
                    cc.starsector.server.runtime.PilotIntentDatagramServer.PROBE_MAX_FRAMES);
        }
        if (probeFrames.isEmpty()) {
            throw new IOException("pilot datagram probe: no listing frame within "
                    + options.readDeadlineMillis() + " ms (budget drop, unreachable, or silent door)");
        }
        // A burst may still be draining duplicate replies past the expected count: read on for
        // one more retry slice so the receipt's probe_datagrams counts EVERY served reply.
        long drainUntil = nowMillis() + HELLO_RETRY_MILLIS * 2L;
        while (nowMillis() < drainUntil) {
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException idle) {
                break;
            }
            DatagramFrameCodec.Frame frame =
                    DatagramFrameCodec.decode(packet.getData(), packet.getLength());
            if (frame != null && frame.channel() == DatagramFrameCodec.WireChannel.CTRL
                    && frame.sessionId() == 0L && packet.getSocketAddress().equals(server)) {
                probeDatagrams++;
            }
        }
    }

    /** The probe's ADMITTED text (a probe never claims a seat). */
    private static final String PROBE_ADMITTED_BREAK =
            "pilot datagram probe answered with an ADMITTED WELCOME (protocol break: a probe never claims)";

    /**
     * The version-13 probe's WELCOME branch: ONE payload from the server address, offered to each
     * burst flight in burst order until one answers non-{@code IGNORE} — {@code SEND} = send that flight's F2 now
     * (+ {@code cookie_challenges}); {@code HELD} = the pair budget kept the pair unsealed, or the flight is at its {@code SEAL_CAP} — nothing to send;
     * {@code REJECTED} = the "pilot datagram probe rejected: " text; {@code ADMITTED} =
     * {@link #PROBE_ADMITTED_BREAK}. Every flight {@code IGNORE} (not ours, malformed, a duplicate challenge) =
     * {@code dropped_undecodable} once for the datagram. A flight that is not the payload's addressee only counts its
     * own {@code ignored()} (a nonce echo names one flight). Main thread, no lock held (the flight's caller law).
     *
     */
    private void onProbeWelcome13(List<Handshake13Flight> flights, byte[] payload) throws IOException {
        for (Handshake13Flight flight : flights) {
            Handshake13Flight.Event event = flight.onWelcome(payload, nowMillis());
            switch (event.kind()) {
                case IGNORE -> {
                    continue;
                }
                case SEND -> {
                    cookieChallenges++;
                    byte[] sealed = event.datagram();
                    socket.send(new DatagramPacket(sealed, sealed.length, server));
                }
                case HELD -> {
                    // the budget kept this pair unsealed, or the flight is at its seal cap: no F2 for it (no retransmit
                    // on the probe path, so no credit either)
                }
                case REJECTED -> throw new IOException("pilot datagram probe rejected: "
                        + rejectReasonOf(event.rejected()));
                case ADMITTED -> throw new IOException(PROBE_ADMITTED_BREAK);
                default -> throw new IllegalStateException("unhandled hs-13 event " + event.kind());
            }
            return;
        }
        droppedUndecodable++;
    }

    /** The probe receipt (the PS drill's contract): geometry, counts, the ids in order. The
     * first six rows mirror the session receipt's identity block. */
    private List<String> probeReceiptLines() {
        List<String> lines = new ArrayList<>();
        lines.add("client_class=" + PilotIntentDatagramClient.class.getName());
        lines.add("host=" + options.host());
        lines.add("port=" + options.port());
        lines.add("probe=true");
        lines.add("connected=false");
        lines.add("fingerprint_carried=" + !options.fingerprint().isEmpty());
        cc.starsector.server.runtime.ListingFrameCodec.ListingDown first =
                probeFrames.isEmpty() ? null : probeFrames.firstEntry().getValue();
        lines.add("probe_burst=" + options.probeBurst());
        lines.add("probe_frames=" + probeFrames.size());
        lines.add("probe_datagrams=" + probeDatagrams);
        lines.add("probe_seq_count=" + (first == null ? 0 : first.seqCount()));
        lines.add("probe_total_entries=" + (first == null ? 0 : first.totalEntries()));
        lines.add("probe_total_available=" + (first == null ? 0 : first.totalAvailable()));
        StringBuilder ids = new StringBuilder();
        for (cc.starsector.server.runtime.ListingFrameCodec.ListingDown frame : probeFrames.values()) {
            for (cc.starsector.server.runtime.ListingFrameCodec.ListingEntry entry : frame.entries()) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(entry.token());
            }
        }
        lines.add("probe_entries=" + ids);
        lines.add("dropped_undecodable=" + droppedUndecodable);
        return lines;
    }

    private List<String> receiptLines(boolean connected, String error) {
        DatagramReliability rel = reliability;
        DatagramReliability.Stats stats = rel == null ? null : rel.stats();
        SeatNameFrameCodec.SeatNames names = seatNames;
        List<String> lines = new ArrayList<>();
        lines.add("client_class=" + PilotIntentDatagramClient.class.getName());
        lines.add("host=" + options.host());
        lines.add("port=" + options.port());
        lines.add("source_ship_id=" + options.shipId());
        lines.add("connected=" + connected);
        lines.add("frames_sent=" + framesSent);
        lines.add("last_aim_deg=" + lastAimDeg);
        lines.add("sent_sha256=" + sentSha256());
        lines.add("own_frames_observed=" + ownFramesObserved);
        lines.add("own_max_disp=" + ownMaxDisp);
        lines.add("own_max_speed=" + ownMaxSpeed);
        lines.add("decl_sent=" + declSent);
        lines.add("brake_frames=" + brakeFrames);
        lines.add("fire_rising_edges=" + fireRisingEdges);
        lines.add("fire_edge_seq_last=" + fireEdgeSeq);
        lines.add("fingerprint_carried=" + !options.fingerprint().isEmpty());
        lines.add("session_id=" + Long.toHexString(sessionId));
        lines.add("starting_round_id=" + startingRoundId);
        lines.add("current_round_id=" + currentRoundId);
        lines.add("rounds_flipped=" + mrstReceived);
        lines.add("snap_fresh_applied=" + snapFreshApplied);
        lines.add("snap_stale_round_dropped=" + snapStaleRoundDropped);
        lines.add("snap_future_buffered=" + snapFutureBuffered);
        lines.add("snap_future_applied=" + snapFutureApplied);
        lines.add("snap_chunks_merged=" + merger.chunksMerged());
        lines.add("snap_mixed_tick_max=" + merger.mixedTickMax());
        lines.add("snap_fragments_merged=" + merger.fragmentsMerged());
        lines.add("snap_frag_tier_spread_max=" + merger.fragTierSpreadMax());
        lines.add("snap_cloak_age_max=" + merger.cloakAgeMax());
        lines.add("snap_stale_forced_merges=" + merger.staleForcedMerges());
        lines.add("snap_awsd_kf=" + merger.awsdKeyframes());
        lines.add("snap_awsd_delta=" + merger.awsdDeltas());
        lines.add("snap_awsd_dropped=" + merger.awsdDropped());
        lines.add("snap_awsd_nack_tx=" + snapAwsdNackTx);
        lines.add("gpee_received=" + gpeeReceived);
        lines.add("edsp_received=" + edspReceived);
        lines.add("seat_state_received=" + seatStateReceived);
        lines.add("hkps_received=" + hkpsReceived);
        lines.add("hkfr_received=" + hkfrReceived);
        lines.add("hkcg_received=" + hkcgReceived);
        lines.add("rapp_received=" + rappReceived);
        lines.add("rapp_rows_received=" + rappRowsReceived);
        StringBuilder rappIds = new StringBuilder();
        for (Long id : rappWireIds) {
            if (rappIds.length() > 0) {
                rappIds.append(',');
            }
            rappIds.append(id);
        }
        lines.add("rapp_wire_ids=" + rappIds);
        lines.add("movr_received=" + movrReceived);
        lines.add("pstb_received=" + pstbReceived);
        lines.add("vcfg_received=" + vcfgReceived); // W12 L2
        lines.add("pstb_tag=" + pstbLastTag);
        StringBuilder movrs = new StringBuilder();
        for (MatchOverFrameCodec.MatchOverRecord record : matchOverRecords) {
            if (movrs.length() > 0) {
                movrs.append(',');
            }
            movrs.append(record.outcome()).append('|').append(record.winnerSide()).append('|')
                    .append(record.simTick());
        }
        lines.add("movr_records=" + movrs);
        lines.add("chat_received=" + chatReceived);
        lines.add("chat_last=" + lastChat);
        // CatalogProof rows: geometry from the first collected frame (codec
        // validates it uniform), entries summed over collected frames; the w-prefix count pins
        // that the catalogue's alphabetical TAIL survived the drip (frame ~113, not just ~1).
        cc.starsector.server.runtime.ListingFrameCodec.ListingDown firstListing =
                listingFrames.isEmpty() ? null : listingFrames.firstEntry().getValue();
        int listingEntrySum = 0;
        int listingWPrefix = 0;
        String listingLastToken = "";
        for (cc.starsector.server.runtime.ListingFrameCodec.ListingDown down : listingFrames.values()) {
            for (cc.starsector.server.runtime.ListingFrameCodec.ListingEntry entry : down.entries()) {
                listingEntrySum++;
                if (entry.token().startsWith("w")) {
                    listingWPrefix++;
                }
                listingLastToken = entry.token();
            }
        }
        lines.add("listing_frames=" + listingFrames.size());
        lines.add("listing_seq_count=" + (firstListing == null ? 0 : firstListing.seqCount()));
        lines.add("listing_entries=" + listingEntrySum);
        lines.add("listing_total_entries=" + (firstListing == null ? 0 : firstListing.totalEntries()));
        lines.add("listing_total_available=" + (firstListing == null ? 0 : firstListing.totalAvailable()));
        lines.add("listing_w_prefix=" + listingWPrefix);
        lines.add("listing_last_token=" + listingLastToken);
        lines.add("listing_other_ignored=" + listingOtherIgnored);
        lines.add("listing_stale_dropped=" + listingStaleDropped);
        lines.add("vdef_received=" + vdefReceived); // Admission VDEF burst
        // W15 v0: the custom-variant lane as THIS seat saw it on the wire.
        lines.add("vdef_custom_ids=" + String.join(",", vdefCustomIds));
        lines.add("vsub_sent=" + vsubSent);
        lines.add("vack_received=" + vackLog.size());
        lines.add("vack_accepted=" + vackAccepted);
        lines.add("vack_rejected=" + vackRejected);
        lines.add("vack_log=" + String.join(",", vackLog));
        lines.add("aimp_received=" + aimpReceived); // AIMP aim-fan frames tolerated
        lines.add("family_tolerated=" + familyToleratedRow()); // XFER:n,MMNT:n,…
        lines.add("stop_file_seen=" + stopFileSeen); // Graceful --stop-file exit
        lines.add("chat_file_lines_sent=" + chatFileLinesSent); // --chat-file lane
        lines.add("seat_names=" + (names == null ? "" : names.seat1Name() + "|" + names.seat2Name()));
        StringBuilder records = new StringBuilder();
        for (SeatNameFrameCodec.SeatNameRecord record : seatNameRecords.values()) {
            if (records.length() > 0) {
                records.append(',');
            }
            records.append(record.seatId()).append('|').append(record.shipId()).append('|')
                    .append(record.name());
        }
        lines.add("seat_name_records=" + records);
        lines.add("seat_name_records_received=" + seatNameRecordsReceived);
        lines.add("hello_retransmits=" + helloRetransmits);
        lines.add("cookie_challenges=" + cookieChallenges); // Count only
        lines.add("hello_flags=" + options.helloFlags());
        lines.add("ability_window_held=" + abilityWindowHeld);
        lines.add(hsReceiptRow()); // hs=Handshake13.VERSION
        lines.add("welcome_duplicates=" + welcomeDuplicates);
        lines.add("dropped_undecodable=" + droppedUndecodable);
        lines.add("dropped_bad_session=" + droppedBadSession);
        lines.add("dropped_bad_source=" + droppedBadSource);
        lines.add("dropped_unexpected_channel=" + droppedUnexpectedChannel);
        lines.add("paused_outbound_dropped=" + pausedOutboundDropped);
        lines.add("ctrl_sent=" + (stats == null ? 0 : stats.ctrlSent()));
        lines.add("ctrl_delivered=" + (stats == null ? 0 : stats.ctrlDelivered()));
        lines.add("ctrl_retransmits=" + (stats == null ? 0 : stats.ctrlRetransmits()));
        lines.add("snap_sent_wire=" + (stats == null ? 0 : stats.snapSent()));
        lines.add("snap_hi_seen=" + (stats == null ? 0 : stats.snapHiSeen()));
        lines.add("bye_sent=" + byeSent);
        lines.add("peer_said_bye=" + peerSaidBye);
        lines.add("bye_clean=" + (byeAcked || peerSaidBye));
        lines.add("error=" + error);
        return lines;
    }

    /** Receipt when construction itself failed (no client instance to snapshot). */
    private static List<String> minimalFailureLines(String error) {
        List<String> lines = new ArrayList<>();
        lines.add("client_class=" + PilotIntentDatagramClient.class.getName());
        lines.add("connected=false");
        lines.add("frames_sent=0");
        lines.add("error=" + error);
        return lines;
    }

    private static void writeReceipt(Path receiptPath, List<String> lines) throws IOException {
        Path parent = receiptPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(receiptPath, lines, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static String errorText(Exception e) {
        return e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
    }

    // ------------------------------------------------------------------ small helpers

    private static void sleepQuietly(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("pilot datagram client wait interrupted", interrupted);
        }
    }

    /** {@code XFER:n,MMNT:n} — fourcc-rendered, ascending by magic. */
    private String familyToleratedRow() {
        StringBuilder row = new StringBuilder();
        for (java.util.Map.Entry<Integer, Long> entry : familyTolerated.entrySet()) {
            if (row.length() > 0) {
                row.append(',');
            }
            row.append(fourcc(entry.getKey())).append(':').append(entry.getValue());
        }
        return row.toString();
    }

    private static String fourcc(int magic) {
        char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            int b = (magic >>> (24 - 8 * i)) & 0xFF;
            chars[i] = (b >= 0x20 && b <= 0x7E) ? (char) b : '?';
        }
        return new String(chars);
    }

    private static int payloadMagic(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES) {
            return 0;
        }
        return ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
    }

    /** ONE monotonic clock (the reliability core requires a single source; server-endpoint mirror). */
    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
