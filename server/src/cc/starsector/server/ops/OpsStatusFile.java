package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.runtime.Handshake13;
import cc.starsector.server.runtime.MagicSeamFollow;

/**
 * Ops snapshot surface: the machine-readable file {@code serveNPeer} rewrites (1 s
 * wall-clock throttle) for the operator GUI — connection truth, capacity meters, and the verb
 * drop's self-discovery, all in one versioned {@code ccops.v=1} k=v grammar. The GUI side is
 * {@link OpsSnapshot#parse}; the round-trip is gated game-free by {@code OpsStatusContract}.
 *
 * <p>Write mechanics: whole-file REWRITE via temp sibling + ATOMIC_MOVE (the
 * {@code DedicatedCombatServerMain.writePortFile} precedent) so a reader never observes a
 * half-written snapshot. The PATH resolves LOUD at serve boot ({@link Path#of} THROWs — house
 * rule); after boot every write is fail-soft (first failure logs once, the serve never dies for
 * its status file). No heartbeat thread: {@code stamp_ms} goes stale while the serve blocks in
 * the lobby await, and that staleness IS the GUI's "listening, nobody joined yet" signal.
 *
 * <p>Window meters ({@code tick_per_s}, {@code util_pct}, {@code work_max_ms},
 * {@code sent_bps}/{@code recv_bps}, per-seat bps twins) are deltas between successive WRITES;
 * cumulative fields ({@code bytes_*_total}, {@code overrun_frames}, {@code send_failures}) ride
 * through as-is. Per-seat bps baselines key on seatId; a takeover's fresh counters clamp to 0
 * instead of going negative.
 */
public final class OpsStatusFile {

    public static final int VERSION = 1;
    /** Writes are throttled to at most one per this many wall millis. */
    public static final long WRITE_INTERVAL_MILLIS = 1000L;

    /** One connected-seat row (order = the serve loop's live-session order).
     * {@code name} = the seat's wire-announced player name ("" = none announced); peer text,
     * bounded by {@link #sanitize} on write like {@code clientId}. */
    public record SeatRow(int seatId, String clientId, String endpoint, String ship,
            boolean live, long txBytes, long rxBytes, int ctrlDepth, String name,
            String custom, String customHull, int customOp, String customName, String customs) {
        public SeatRow {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(ship, "ship");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(custom, "custom");
            Objects.requireNonNull(customHull, "customHull");
            Objects.requireNonNull(customName, "customName");
            Objects.requireNonNull(customs, "customs");
        }

        /** The older arity (no {@code customs} list — the seat's ACCEPTED, not-banned
         * minted ids, comma-joined; "" = none). */
        public SeatRow(int seatId, String clientId, String endpoint, String ship,
                boolean live, long txBytes, long rxBytes, int ctrlDepth, String name,
                String custom, String customHull, int customOp, String customName) {
            this(seatId, clientId, endpoint, ship, live, txBytes, rxBytes, ctrlDepth, name,
                    custom, customHull, customOp, customName, "");
        }

        /** The pre-step-3(f) arity (no custom block) — the D3 shape. */
        public SeatRow(int seatId, String clientId, String endpoint, String ship,
                boolean live, long txBytes, long rxBytes, int ctrlDepth, String name) {
            this(seatId, clientId, endpoint, ship, live, txBytes, rxBytes, ctrlDepth, name, "", "", -1, "");
        }

        /** The pre-D3 arity (no announced name) — contract fakes + the selftests. */
        public SeatRow(int seatId, String clientId, String endpoint, String ship,
                boolean live, long txBytes, long rxBytes, int ctrlDepth) {
            this(seatId, clientId, endpoint, ship, live, txBytes, rxBytes, ctrlDepth, "");
        }
    }

    /** W15 v0 step 3(f): the global custom-variant counters — {@code custom.count}
     * (accepted, registry truth incl. banned), {@code custom.rejected} (gate + policy + engine +
     * operator refusals), {@code custom.banned}, {@code custom.submissions} (on/off; the lane
     * OFF = {@link #NONE}). Composed on the sim thread from synchronized ledger reads.
     * + the rejected split ({@code rejectedPolicy} = tier/shape/OP refusals at
     * the door, {@code rejectedCaps} = rate / seat-cap / global-cap gate refusals,
     * {@code rejectedEngine} = registrar refusals + faults, {@code rejectedOperator} = off /
     * rejected-seat refusals; the four sum to {@code rejected}), {@code pending} (deposited, not
     * yet minted — counts against the global cap with {@code count}), {@code capGlobal}, the
     * serve's policy knobs ({@code tier}/{@code perSeat}/{@code sModCap}/{@code dMods} — static
     * config, "-"/0 when OFF) and the export readback ({@code exported} = inbox copies the
     * writer really landed, {@code exportFailed} = refused-before or failed-at the writer).
     * + the kept store ("Keep for everyone") —
     * {@code kept} = kept ships on {@code /ship}, {@code keptIds} = the stems the last kept scan read,
     * {@code keptFailed} = kept verbs refused before + kept jobs refused / failed at the writer,
     * {@code keptLast} = the writer's last kept verdict ("-" = none), {@code inboxIds} = the inbox's
     * exported / forgotten-kept names (never opened), {@code keptOverCap} / {@code inboxOverCap} = the last
     * scan hit a bound (loud, never a silent subset); OFF = zeros, empty, "-".
     * + the R3b recording seam's two refusal meters — {@code frameEncodeFailed} =
     * submit frames the reader thread's canonical re-encode refused (their reel rows carry no body, so a re-sim
     * refuses the reel) and {@code vsubDecodeRefused} = recorded frames the re-sim's own decode refused. */
    /** One P27 ban row ({@code kind} =
     *  {@code cid|src}, {@code key} = the BanSet key form, {@code origin} = {@code op|admin}). */
    public record BanRow(String kind, String key, long atMillis, String origin) {}

    /** Ban rows written per status file (the rest counts into {@code bans_more=}). */
    public static final int MAX_BAN_ROWS = 64;

    public record CustomStats(int count, int rejected, int banned, boolean submissions,
            int rejectedPolicy, int rejectedCaps, int rejectedEngine, int rejectedOperator,
            int pending, int capGlobal, String tier, int perSeat, String sModCap, String dMods,
            long exported, long exportFailed, int kept, List<String> keptIds, long keptFailed,
            String keptLast, List<String> inboxIds, boolean keptOverCap, boolean inboxOverCap,
            int frameEncodeFailed, int vsubDecodeRefused) {
        public static final CustomStats NONE = new CustomStats(0, 0, 0, false,
                0, 0, 0, 0, 0, 0, "-", 0, "-", "-", 0L, 0L);

        /** The 23-arg arity (before the R3b refusal meters): both meters read 0. */
        public CustomStats(int count, int rejected, int banned, boolean submissions,
                int rejectedPolicy, int rejectedCaps, int rejectedEngine, int rejectedOperator,
                int pending, int capGlobal, String tier, int perSeat, String sModCap, String dMods,
                long exported, long exportFailed, int kept, List<String> keptIds, long keptFailed,
                String keptLast, List<String> inboxIds, boolean keptOverCap, boolean inboxOverCap) {
            this(count, rejected, banned, submissions, rejectedPolicy, rejectedCaps, rejectedEngine, rejectedOperator,
                    pending, capGlobal, tier, perSeat, sModCap, dMods, exported, exportFailed, kept, keptIds, keptFailed,
                    keptLast, inboxIds, keptOverCap, inboxOverCap, 0, 0);
        }

        public CustomStats {
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(sModCap, "sModCap");
            Objects.requireNonNull(dMods, "dMods");
            keptIds = List.copyOf(Objects.requireNonNull(keptIds, "keptIds"));
            Objects.requireNonNull(keptLast, "keptLast");
            inboxIds = List.copyOf(Objects.requireNonNull(inboxIds, "inboxIds"));
        }

        /** The 16-arg arity (the kept rows read as OFF: zeros, empty, "-"). */
        public CustomStats(int count, int rejected, int banned, boolean submissions,
                int rejectedPolicy, int rejectedCaps, int rejectedEngine, int rejectedOperator,
                int pending, int capGlobal, String tier, int perSeat, String sModCap, String dMods,
                long exported, long exportFailed) {
            this(count, rejected, banned, submissions, rejectedPolicy, rejectedCaps, rejectedEngine,
                    rejectedOperator, pending, capGlobal, tier, perSeat, sModCap, dMods, exported, exportFailed,
                    0, List.of(), 0L, "-", List.of(), false, false);
        }

        /** The step-3(f) arity (the four originals; the rows read as OFF/zeros). */
        public CustomStats(int count, int rejected, int banned, boolean submissions) {
            this(count, rejected, banned, submissions, 0, 0, 0, 0, 0, 0, "-", 0, "-", "-", 0L, 0L);
        }
    }

    /** Ids per kept status row — the catalog lane's own cap
     *  ({@code KEPT_CAP}, 32) restated as the status file's bound. */
    public static final int KEPT_ID_ROW_CAP = 32;
    /** Ids per inbox status row = twice {@code INBOX_LIST_CAP} (32): the lane lists the inbox PER PREFIX -
     *  {@code kept-…} names first, then the exports, each cut to 32 on its own - so a row bound of 32 would drop the last
     *  exports with no over-cap word whenever both prefixes together pass 32; the contract row pins the
     *  pair. The over-cap rows below also flip when a row cut happens here. */
    public static final int INBOX_ID_ROW_CAP = 64;

    /**
     * The host's ship-list rows ({@code ships.*}) - {@code baseIds} =
     * every ship that comes with the server (listed or hidden), {@code hiddenIds} = the ones the host took off
     * {@code /ship}, both canonical; {@code overCap} = the hidden-ships file holds ids past its cap (not in
     * effect); {@code last} = the last ship verdict; {@code persistFailed} = saves of the hidden list that failed.
     * No supplier attached = no {@code ships.*} key at all (a reader then knows the server has no ship rows).
     */
    public record ShipStats(List<String> baseIds, List<String> hiddenIds, boolean overCap, String last,
            long persistFailed) {
        public ShipStats {
            baseIds = List.copyOf(Objects.requireNonNull(baseIds, "baseIds"));
            hiddenIds = List.copyOf(Objects.requireNonNull(hiddenIds, "hiddenIds"));
            Objects.requireNonNull(last, "last");
        }
    }

    /**
     * The MagicLib seam facts behind the match-level {@code magic_seam=} key
     * and each seat's {@code seat.N.magic=} / {@code seat.N.magic_why=} pair. {@code receiptOrNull} = the serve's
     * seam receipt ({@code off} | {@code armed} | {@code applied:<16 hex>} | {@code disarmed:<reason>}; null = no
     * receipt, written as {@link #MAGIC_SEAM_UNSET}); {@code doorArmed} = this match's door fingerprint is set;
     * {@code helloBitsBySeat} maps a seat id (>= 0, 0 = an observer) to that seat's latest admitted HELLO flag bits
     * (0 = none). WHY these three inputs: they are exactly the pilot mark's FOLLOW inputs, and every seat's verdict
     * is {@link MagicSeamFollow#whyOrNull} over them - the one predicate, never a second one - so the host reads
     * "follows" for the same seats the serve follows.
     */
    public record MagicSeam(String receiptOrNull, boolean doorArmed, IntUnaryOperator helloBitsBySeat) {
        public MagicSeam {
            Objects.requireNonNull(helloBitsBySeat, "helloBitsBySeat");
        }
    }

    /** {@code magic_seam=} when the serve holds no seam receipt (nothing decided the seam at its boot). */
    public static final String MAGIC_SEAM_UNSET = "unset";
    /**
     * {@code pilot_ship=} when the serve's JVM holds no player-ship receipt (no premain decided the switch). The key
     * carries the receipt sysprop ({@link cc.starsector.server.transform.TransformRegistry#PILOT_SHIP_RECEIPT_SYSPROP})
     * as read at each compose: {@code off} | {@code armed} | {@code applied:<k>} | {@code refused:<reason>} |
     * {@code latched:<reason>}.
     */
    public static final String PILOT_SHIP_UNSET = "unset";
    /** {@code seat.N.magic=}: the serve follows this pilot's own MagicLib decisions on the seat's hull. */
    public static final String MAGIC_FOLLOWS = "follows";
    /** {@code seat.N.magic=}: the seat's game reports its MagicLib states (HELLO bit 0x08), yet the serve does not
     *  follow them; {@code seat.N.magic_why=} says why. */
    public static final String MAGIC_REPORTS = "reports";
    /** {@code seat.N.magic=}: the seat's game reports no MagicLib states; also {@code magic_why=} for a followed seat. */
    public static final String MAGIC_NONE = "-";

    /** The seat's {@code magic=} cell from its FOLLOW verdict ({@code why}
     *  null = followed) and its HELLO flag bits (0x08 = its game reports its MagicLib states). */
    static String magicCell(String why, int helloBits) {
        if (why == null) {
            return MAGIC_FOLLOWS;
        }
        return (helloBits & Handshake13.FLAG_MAGIC_UP) != 0 ? MAGIC_REPORTS : MAGIC_NONE;
    }

    /** The {@code ships.base_ids} / {@code ships.hidden_ids} bound (more = {@code ships.over_cap=1}). */
    public static final int SHIP_ID_ROW_CAP = 64;
    /** The {@code ships.last} bound (the cut keeps a visible ellipsis inside it). */
    public static final int SHIP_LAST_MAX_CHARS = 160;
    private static final java.util.concurrent.atomic.AtomicBoolean SHIP_ID_SKIP_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** One comma-joined ship-id row: ≤ {@link #SHIP_ID_ROW_CAP} ids, each through {@link #sanitize}; empty = "-".
     *  An id the row grammar cannot carry (empty, the {@code "-"} empty marker, or holding the {@code ,}
     *  separator) is skipped, loud once per process: it would split into two ids or read as an empty row. */
    private static String shipIdRow(List<String> ids) {
        StringBuilder row = new StringBuilder();
        int written = 0;
        int skipped = 0;
        for (String id : ids) {
            if (written >= SHIP_ID_ROW_CAP) {
                break;
            }
            String clean = sanitize(id);
            if (clean.isEmpty() || "-".equals(clean) || clean.indexOf(',') >= 0) {
                skipped++;
                continue;
            }
            if (written > 0) {
                row.append(',');
            }
            row.append(clean);
            written++;
        }
        if (skipped > 0 && SHIP_ID_SKIP_LOGGED.compareAndSet(false, true)) {
            System.out.println("ops-file ships_id_skipped count=" + skipped
                    + " (an id the status row cannot carry: it stays on the server, the console does not list it)");
        }
        return written == 0 ? "-" : row.toString();
    }

    /** One comma-joined id row: ≤ {@code cap} ids ({@link #KEPT_ID_ROW_CAP} / {@link #INBOX_ID_ROW_CAP}), each through
     *  {@link #sanitize}; empty = "-". */
    private static String keptIdRow(List<String> ids, int cap) {
        if (ids.isEmpty()) {
            return "-";
        }
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < ids.size() && i < cap; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(sanitize(ids.get(i)));
        }
        return row.toString();
    }

    private final Path file;
    private final Path tmp;
    private final String commandFile;
    private final LongSupplier clockMillis;
    private final long pid;
    private final long bootMillis;
    // Decoupled mode: run-phase rewrites hand the composed lines to a
    // latest-wins writer thread (the file is a CURRENT-status surface — a superseded snapshot
    // that never reached the disk is correct to skip, counted). lobby/final stay synchronous
    // (outside the tick path; final must land before exit). The sync temp+ATOMIC_MOVE write
    // measured 94.7 ms worst on the HDD even with everything else buffered.
    private final boolean decoupled;
    private final java.util.concurrent.atomic.AtomicReference<List<String>> pendingRun =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong runWrites =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong runSkips =
            new java.util.concurrent.atomic.AtomicLong();
    private Thread runWriter; // serve-thread only touch points (ensure + final join)
    private volatile boolean runWriterStop;

    private volatile boolean writeFailureLogged; // volatile: the run writer thread logs too
    private boolean everWrote;
    private long lastWriteMillis;
    // Scale-lane row 2: per-phase window stats, snapshot-and-reset on every compose (null =
    // serve without the feed; the grammar then carries the EMPTY zeros so keys stay fixed).
    private PhaseWindowStats phaseStats;
    /** Step 3(f): the custom-variant counters (null = the lane's OFF zeros; keys stay fixed). */
    private Supplier<CustomStats> customStats;
    private Supplier<List<BanRow>> banRows; // P27 ban rows source
    private Supplier<ShipStats> shipStats; // null = no ships.* rows
    private Supplier<MagicSeam> magicSeam; // null = no magic_seam / seat.N.magic rows

    // ---- last-write window baselines ----
    private long winSimTick = -1L;
    private long winWorkFrames;
    private long winWorkNanos;
    private long winBytesSent;
    private long winBytesRecv;
    private long winWorkMaxNanos; // running max of frameWorkNanos since the last write
    private final Map<Integer, long[]> winSeatBytes = new HashMap<>(); // seatId -> {tx, rx}

    // ---- run-state memory (writeFinal re-composes from the last onFrame's values) ----
    private String lastHost = "";
    private int lastPort;
    private int lastSeatsTotal;
    private int lastRound;
    private int lastFrame;
    private long lastSimTick = -1L;
    private long lastWorkFrames;
    private long lastWorkNanos;
    private long lastFrameTargetNanos;
    private int lastOverrunFrames;
    private long lastDebtNanos; // Current pacer debt (0 when on schedule)

    /** @throws java.nio.file.InvalidPathException on an unusable path (loud at serve boot). */
    public OpsStatusFile(String path, String commandFileOrOff) {
        this(path, commandFileOrOff, System::currentTimeMillis, false);
    }

    /** Decoupled-capable form: run-phase writes ride a writer thread. */
    public OpsStatusFile(String path, String commandFileOrOff, boolean decoupled) {
        this(path, commandFileOrOff, System::currentTimeMillis, decoupled);
    }

    /** Test seam: an injectable wall clock so the throttle/window math is deterministic. */
    public OpsStatusFile(String path, String commandFileOrOff, LongSupplier clockMillis) {
        this(path, commandFileOrOff, clockMillis, false);
    }

    /** Test seam, decoupled-capable (clock + writer-thread mode both injectable). */
    public OpsStatusFile(String path, String commandFileOrOff, LongSupplier clockMillis,
            boolean decoupled) {
        this.decoupled = decoupled;
        this.file = Path.of(path.trim());
        this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
        this.commandFile = commandFileOrOff == null || commandFileOrOff.isEmpty()
                ? "off" : commandFileOrOff;
        this.clockMillis = clockMillis;
        this.pid = ProcessHandle.current().pid();
        this.bootMillis = clockMillis.getAsLong();
    }

    public Path file() {
        return file;
    }

    /** Serve boot wiring (scale-lane row 2): the loop feeds this stats object per frame; every
     * compose here snapshots-and-resets it, aligning the phase window with the write window. */
    public void attachPhaseStats(PhaseWindowStats stats) {
        this.phaseStats = stats;
    }

    /** Step 3(f): the custom-variant counters supplier, read at each compose (null = {@link CustomStats#NONE}). */
    public void attachCustomStats(Supplier<CustomStats> stats) {
        this.customStats = stats;
    }

    /** The P27 ban rows supplier, read at each compose
     *  (null = no rows: {@code bans=0}). */
    public void attachBans(Supplier<List<BanRow>> bans) {
        this.banRows = bans;
    }

    /** The host's ship-list rows supplier, read at each compose
     *  (null = no {@code ships.*} key at all). */
    public void attachShipStats(Supplier<ShipStats> ships) {
        this.shipStats = ships;
    }

    /** The MagicLib seam facts supplier, read at each compose (null = no
     *  {@code magic_seam} key and no {@code seat.N.magic} rows: a reader then shows no MagicLib line or cell). */
    public void attachMagicSeam(Supplier<MagicSeam> seam) {
        this.magicSeam = seam;
    }

    /** Forced lobby snapshot right after the door opens (phase=lobby, no sessions yet). */
    public void writeLobby(String host, int port, int seatsTotal) {
        lastHost = host;
        lastPort = port;
        lastSeatsTotal = seatsTotal;
        composeAndWrite("lobby", List.of(), 0L, 0L, 0L);
    }

    /**
     * Per-frame feed from the serve loop's (m) bookkeeping site. Cheap while throttled (one
     * clock read + one max fold); on the 1 s boundary composes phase=run and rewrites the file.
     * The seat-row supplier is only invoked on an actual write.
     */
    public void onFrame(String host, int port, int seatsTotal, int round, int frame, long simTick,
            long frameWorkNanos, long workFrames, long workNanosTotal, long frameTargetNanos,
            long bytesSentTotal, long bytesRecvTotal, int overrunFrames, long debtNowNanos,
            long sendFailures, Supplier<List<SeatRow>> seats) {
        if (frameWorkNanos > winWorkMaxNanos) {
            winWorkMaxNanos = frameWorkNanos;
        }
        lastDebtNanos = debtNowNanos;
        lastHost = host;
        lastPort = port;
        lastSeatsTotal = seatsTotal;
        lastRound = round;
        lastFrame = frame;
        lastSimTick = simTick;
        lastWorkFrames = workFrames;
        lastWorkNanos = workNanosTotal;
        lastFrameTargetNanos = frameTargetNanos;
        lastOverrunFrames = overrunFrames;
        if (clockMillis.getAsLong() - lastWriteMillis < WRITE_INTERVAL_MILLIS) {
            return;
        }
        composeAndWrite("run", seats.get(), bytesSentTotal, bytesRecvTotal, sendFailures);
    }

    /** Forced end-of-serve snapshot beside the goodbye fan (phase=final, throttle bypassed). */
    public void writeFinal(long bytesSentTotal, long bytesRecvTotal, long sendFailures,
            Supplier<List<SeatRow>> seats) {
        // Decoupled: retire the run writer FIRST (it drains any pending snapshot before
        // exiting) so the synchronous final compose below is the file's last write. If the
        // writer is still hung in write() past the bounded join (dead disk), SKIP the final
        // write: two threads on the same tmp sibling could ATOMIC_MOVE a torn file live,
        // and this is a best-effort surface (fail-soft law).
        if (!retireRunWriter()) {
            System.out.println("ops-file final_skipped reason=run_writer_hung path=" + file);
            return;
        }
        composeAndWrite("final", seats.get(), bytesSentTotal, bytesRecvTotal, sendFailures);
    }

    /**
     * Retire the decoupled run writer — idempotent; the serve's {@code finally} calls this on
     * EVERY path (an exception before writeFinal must not leak the
     * thread). Returns false only while the writer is still alive after the bounded join.
     */
    public boolean retireRunWriter() {
        if (runWriter == null) {
            return true;
        }
        runWriterStop = true;
        try {
            runWriter.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (runWriter.isAlive()) {
            return false;
        }
        if (pendingRun.getAndSet(null) != null) {
            runSkips.incrementAndGet();
        }
        return true;
    }

    /** Teardown truth: run-phase snapshots the writer thread landed. */
    public long decoupledRunWrites() {
        return runWrites.get();
    }

    /** Run-phase snapshots superseded before reaching the disk (latest-wins skips). */
    public long decoupledRunSkips() {
        return runSkips.get();
    }

    private void composeAndWrite(String phase, List<SeatRow> seats,
            long bytesSentTotal, long bytesRecvTotal, long sendFailures) {
        long now = clockMillis.getAsLong();
        long wallDeltaMs = everWrote ? now - lastWriteMillis : 0L;
        double tickPerS = 0.0d;
        double utilPct = 0.0d;
        long sentBps = 0L;
        long recvBps = 0L;
        if (wallDeltaMs > 0L) {
            // winSimTick may be the pre-first-publish -1: the first run window then counts
            // from tick 0 inclusive (it also spans the lobby wait — a one-window transient).
            if (lastSimTick >= winSimTick) {
                tickPerS = (lastSimTick - winSimTick) * 1000.0d / wallDeltaMs;
            }
            long framesDelta = lastWorkFrames - winWorkFrames;
            if (framesDelta > 0L && lastFrameTargetNanos > 0L) {
                utilPct = 100.0d * (lastWorkNanos - winWorkNanos)
                        / ((double) framesDelta * lastFrameTargetNanos);
            }
            sentBps = Math.max(0L, (bytesSentTotal - winBytesSent) * 1000L / wallDeltaMs);
            recvBps = Math.max(0L, (bytesRecvTotal - winBytesRecv) * 1000L / wallDeltaMs);
        }
        List<SeatRow> rows = seats == null ? List.of() : seats;
        long[][] seatBps = new long[rows.size()][2];
        for (int i = 0; i < rows.size(); i++) {
            SeatRow row = rows.get(i);
            long[] base = winSeatBytes.get(row.seatId());
            if (base != null && wallDeltaMs > 0L) {
                seatBps[i][0] = Math.max(0L, (row.txBytes() - base[0]) * 1000L / wallDeltaMs);
                seatBps[i][1] = Math.max(0L, (row.rxBytes() - base[1]) * 1000L / wallDeltaMs);
            }
        }
        PhaseWindowStats.Snapshot phaseWin = phaseStats == null
                ? PhaseWindowStats.Snapshot.EMPTY : phaseStats.snapshotAndReset();
        CustomStats custom = customStats == null ? null : customStats.get();
        List<String> lines = composeLines(now, pid, phase, lastHost, lastPort,
                (now - bootMillis) / 1000L, lastRound, lastFrame, lastSimTick, lastSeatsTotal,
                tickPerS, utilPct, winWorkMaxNanos / 1_000_000.0d,
                bytesSentTotal, bytesRecvTotal, sentBps, recvBps,
                lastOverrunFrames, lastDebtNanos / 1_000_000.0d, sendFailures,
                commandFile, rows, seatBps, phaseWin, custom == null ? CustomStats.NONE : custom,
                banRows == null ? null : banRows.get(), shipStats == null ? null : shipStats.get(),
                magicSeam == null ? null : magicSeam.get());
        if ("run".equals(phase) && phaseWin.frames() > 0L) {
            // 1 Hz mirror: the file above is a latest-wins REWRITE — this line is what keeps
            // the per-window phase timeline in archived serve logs (rides the stdout lane).
            System.out.println(phaseWin.opsLine());
        }
        // Baselines roll on compose (not on write success) — a failed write costs one stale
        // window, never a compounding one.
        everWrote = true;
        lastWriteMillis = now;
        winSimTick = lastSimTick;
        winWorkFrames = lastWorkFrames;
        winWorkNanos = lastWorkNanos;
        winBytesSent = bytesSentTotal;
        winBytesRecv = bytesRecvTotal;
        winWorkMaxNanos = 0L;
        winSeatBytes.clear();
        for (SeatRow row : rows) {
            winSeatBytes.put(row.seatId(), new long[] {row.txBytes(), row.rxBytes()});
        }
        if (decoupled && "run".equals(phase)) {
            if (pendingRun.getAndSet(lines) != null) {
                runSkips.incrementAndGet(); // superseded before it hit the disk: latest-wins
            }
            ensureRunWriter();
            return;
        }
        try {
            write(lines);
        } catch (IOException | RuntimeException failSoft) {
            logWriteFailure(failSoft);
        }
    }

    private void ensureRunWriter() {
        if (runWriter != null) {
            return; // serve-thread-only caller
        }
        runWriter = new Thread(() -> {
            while (true) {
                List<String> lines = pendingRun.getAndSet(null);
                if (lines != null) {
                    try {
                        write(lines);
                        runWrites.incrementAndGet();
                    } catch (IOException | RuntimeException failSoft) {
                        logWriteFailure(failSoft);
                    }
                } else if (runWriterStop) {
                    return; // stop is only honored on an empty slot: the last snapshot lands
                } else {
                    java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
                }
            }
        }, "cc-ops-writer");
        runWriter.setDaemon(true);
        runWriter.start();
    }

    private void logWriteFailure(Throwable failSoft) {
        if (!writeFailureLogged) {
            writeFailureLogged = true;
            System.out.println("ops-file write_failed path=" + file
                    + " error=" + failSoft.getClass().getName()
                    + ": " + (failSoft.getMessage() == null ? "" : failSoft.getMessage())
                    + " (fail-soft: the serve continues; first failure logged only)");
        }
    }

    private void write(List<String> lines) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(tmp, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The pure grammar core (game-free-goldenable): fixed key order, {@link Locale#ROOT}
     * numerics, one {@code seat.N.*} block per row (N = 1..count in row order). {@code clients}
     * counts rows whose {@code live} flag is true. {@code seatBps[i] = {txBps, rxBps}} for
     * {@code seats.get(i)}.
     */
    public static List<String> composeLines(long stampMs, long pid, String phase, String host,
            int port, long upSeconds, int round, int frame, long simTick, int seatsTotal,
            double tickPerS, double utilPct, double workMaxMs,
            long bytesSentTotal, long bytesRecvTotal, long sentBps, long recvBps,
            int overrunFrames, double debtMs, long sendFailures, String commandFile,
            List<SeatRow> seats, long[][] seatBps, PhaseWindowStats.Snapshot phaseWin) {
        return composeLines(stampMs, pid, phase, host, port, upSeconds, round, frame, simTick, seatsTotal,
                tickPerS, utilPct, workMaxMs, bytesSentTotal, bytesRecvTotal, sentBps, recvBps,
                overrunFrames, debtMs, sendFailures, commandFile, seats, seatBps, phaseWin, CustomStats.NONE);
    }

    /** {@link #composeLines} + the step 3(f) custom-variant block ({@code custom.*} globals after
     * {@code command_file}; {@code seat.N.custom/hull/op/custom_name} after {@code ctrl_depth}). */
    public static List<String> composeLines(long stampMs, long pid, String phase, String host,
            int port, long upSeconds, int round, int frame, long simTick, int seatsTotal,
            double tickPerS, double utilPct, double workMaxMs,
            long bytesSentTotal, long bytesRecvTotal, long sentBps, long recvBps,
            int overrunFrames, double debtMs, long sendFailures, String commandFile,
            List<SeatRow> seats, long[][] seatBps, PhaseWindowStats.Snapshot phaseWin,
            CustomStats custom) {
        return composeLines(stampMs, pid, phase, host, port, upSeconds, round, frame, simTick,
                seatsTotal, tickPerS, utilPct, workMaxMs, bytesSentTotal, bytesRecvTotal, sentBps,
                recvBps, overrunFrames, debtMs, sendFailures, commandFile, seats, seatBps, phaseWin,
                custom, null);
    }

    /** {@link #composeLines} + the P27 ban
     *  block AFTER the seat blocks — {@code bans=<n>}, then {@code ban.<i>.kind/key/at/origin}
     *  for the first {@value #MAX_BAN_ROWS} rows (sanitized like the seat rows) and
     *  {@code bans_more=<n>} when truncated. Additive keys (no version bump); {@code bans == null}
     *  (the pre-P27 overloads, their goldens) omits the block — absent reads as zero bans. */
    public static List<String> composeLines(long stampMs, long pid, String phase, String host,
            int port, long upSeconds, int round, int frame, long simTick, int seatsTotal,
            double tickPerS, double utilPct, double workMaxMs,
            long bytesSentTotal, long bytesRecvTotal, long sentBps, long recvBps,
            int overrunFrames, double debtMs, long sendFailures, String commandFile,
            List<SeatRow> seats, long[][] seatBps, PhaseWindowStats.Snapshot phaseWin,
            CustomStats custom, List<BanRow> bans) {
        return composeLines(stampMs, pid, phase, host, port, upSeconds, round, frame, simTick, seatsTotal,
                tickPerS, utilPct, workMaxMs, bytesSentTotal, bytesRecvTotal, sentBps, recvBps, overrunFrames, debtMs,
                sendFailures, commandFile, seats, seatBps, phaseWin, custom, bans, null);
    }

    /** {@link #composeLines} + the host's ship-list rows right after the
     *  kept-store rows - {@code ships.base_ids} / {@code ships.hidden_ids} (≤ {@value #SHIP_ID_ROW_CAP} ids each),
     *  {@code ships.over_cap} (0/1: the hidden file is over its cap, or a row was cut at its bound),
     *  {@code ships.last} (≤ {@value #SHIP_LAST_MAX_CHARS} chars, sanitized; none = {@code -}) and
     *  {@code ships.persist_failed}. Additive optional keys (no version bump); {@code ships == null} writes none. */
    public static List<String> composeLines(long stampMs, long pid, String phase, String host,
            int port, long upSeconds, int round, int frame, long simTick, int seatsTotal,
            double tickPerS, double utilPct, double workMaxMs,
            long bytesSentTotal, long bytesRecvTotal, long sentBps, long recvBps,
            int overrunFrames, double debtMs, long sendFailures, String commandFile,
            List<SeatRow> seats, long[][] seatBps, PhaseWindowStats.Snapshot phaseWin,
            CustomStats custom, List<BanRow> bans, ShipStats ships) {
        return composeLines(stampMs, pid, phase, host, port, upSeconds, round, frame, simTick, seatsTotal,
                tickPerS, utilPct, workMaxMs, bytesSentTotal, bytesRecvTotal, sentBps, recvBps, overrunFrames, debtMs,
                sendFailures, commandFile, seats, seatBps, phaseWin, custom, bans, ships, null);
    }

    /** {@link #composeLines} + the MagicLib seam rows - {@code magic_seam=}
     *  (the receipt, sanitized; {@link #MAGIC_SEAM_UNSET} without one) right before {@code seat.count=}, and per seat
     *  {@code magic=} ({@link #MAGIC_FOLLOWS} | {@link #MAGIC_REPORTS} | {@link #MAGIC_NONE}) + {@code magic_why=}
     *  (the {@link MagicSeamFollow#whyOrNull} token, {@link #MAGIC_NONE} when followed) after {@code customs=}; and
     *  the player-ship receipt {@code pilot_ship=} ({@link #PILOT_SHIP_UNSET} without one) right before
     *  {@code magic_seam=}. Additive optional keys (no version bump); {@code magic == null} writes none. */
    public static List<String> composeLines(long stampMs, long pid, String phase, String host,
            int port, long upSeconds, int round, int frame, long simTick, int seatsTotal,
            double tickPerS, double utilPct, double workMaxMs,
            long bytesSentTotal, long bytesRecvTotal, long sentBps, long recvBps,
            int overrunFrames, double debtMs, long sendFailures, String commandFile,
            List<SeatRow> seats, long[][] seatBps, PhaseWindowStats.Snapshot phaseWin,
            CustomStats custom, List<BanRow> bans, ShipStats ships, MagicSeam magic) {
        int clients = 0;
        for (SeatRow row : seats) {
            if (row.live()) {
                clients++;
            }
        }
        List<String> lines = new ArrayList<>(38 + seats.size() * 10);
        lines.add("ccops.v=" + VERSION);
        lines.add("stamp_ms=" + stampMs);
        lines.add("pid=" + pid);
        lines.add("phase=" + phase);
        lines.add("host=" + sanitize(host));
        lines.add("port=" + port);
        lines.add("up_s=" + upSeconds);
        lines.add("round=" + round);
        lines.add("frame=" + frame);
        lines.add("sim_tick=" + simTick);
        lines.add("seats_total=" + seatsTotal);
        lines.add("clients=" + clients);
        lines.add("tick_per_s=" + String.format(Locale.ROOT, "%.1f", tickPerS));
        lines.add("util_pct=" + String.format(Locale.ROOT, "%.1f", utilPct));
        lines.add("work_max_ms=" + String.format(Locale.ROOT, "%.2f", workMaxMs));
        // Scale-lane row 2: per-phase window stats (ms, %.3f — the brackets are µs-scale).
        lines.add("ph.frames=" + phaseWin.frames());
        for (int p = 0; p < PhaseWindowStats.PHASES; p++) {
            String pp = "ph." + PhaseWindowStats.PHASE_NAMES[p] + ".";
            lines.add(pp + "mean_ms="
                    + String.format(Locale.ROOT, "%.3f", phaseWin.meanMs()[p]));
            lines.add(pp + "p95_ms="
                    + String.format(Locale.ROOT, "%.3f", phaseWin.p95Ms()[p]));
        }
        lines.add("bytes_sent_total=" + bytesSentTotal);
        lines.add("bytes_recv_total=" + bytesRecvTotal);
        lines.add("sent_bps=" + sentBps);
        lines.add("recv_bps=" + recvBps);
        lines.add("overrun_frames=" + overrunFrames);
        // Current pacer debt (ms) at the last fed frame; 0 = on schedule.
        lines.add("debt_ms=" + String.format(Locale.ROOT, "%.1f", debtMs));
        lines.add("send_failures=" + sendFailures);
        lines.add("command_file=" + sanitize(commandFile));
        // W15 v0 step 3(f): the custom-variant globals — fixed keys, zeros when OFF.
        lines.add("custom.count=" + custom.count());
        lines.add("custom.rejected=" + custom.rejected());
        lines.add("custom.banned=" + custom.banned());
        lines.add("custom.submissions=" + (custom.submissions() ? "on" : "off"));
        // The rejected split (WHY customs fail), the cap headroom pair
        // (count + pending vs cap_global), the serve's policy knobs, the export readback.
        // Additive optional keys (the D3 `name` idiom — never a version bump); no peer text.
        lines.add("custom.rejected.policy=" + custom.rejectedPolicy());
        lines.add("custom.rejected.caps=" + custom.rejectedCaps());
        lines.add("custom.rejected.engine=" + custom.rejectedEngine());
        lines.add("custom.rejected.operator=" + custom.rejectedOperator());
        lines.add("custom.pending=" + custom.pending());
        lines.add("custom.cap_global=" + custom.capGlobal());
        lines.add("custom.tier=" + sanitize(custom.tier()));
        lines.add("custom.per_seat=" + custom.perSeat());
        lines.add("custom.smod_cap=" + sanitize(custom.sModCap()));
        lines.add("custom.dmods=" + sanitize(custom.dMods()));
        lines.add("custom.exported=" + custom.exported());
        lines.add("custom.export_failed=" + custom.exportFailed());
        // The R3b refusal meters — a meter without a reader does not exist. Additive
        // optional keys (the same D3 idiom); numbers only.
        lines.add("custom.frame_encode_failed=" + custom.frameEncodeFailed());
        lines.add("custom.vsub_decode_refused=" + custom.vsubDecodeRefused());
        // The kept store rows — additive optional keys (no version
        // bump); the two over-cap rows keep the store's bound loud.
        lines.add("custom.kept=" + custom.kept());
        lines.add("custom.kept_ids=" + keptIdRow(custom.keptIds(), KEPT_ID_ROW_CAP));
        lines.add("custom.kept_failed=" + custom.keptFailed());
        lines.add("custom.kept_last=" + sanitize(custom.keptLast()));
        lines.add("custom.inbox_ids=" + keptIdRow(custom.inboxIds(), INBOX_ID_ROW_CAP));
        // A row cut here is an over-cap too (the ships.over_cap idiom below) - no list loses names silently.
        lines.add("custom.kept_over_cap=" + (custom.keptOverCap() || custom.keptIds().size() > KEPT_ID_ROW_CAP ? 1 : 0));
        lines.add("custom.inbox_over_cap=" + (custom.inboxOverCap() || custom.inboxIds().size() > INBOX_ID_ROW_CAP ? 1 : 0));
        if (ships != null) {
            lines.add("ships.base_ids=" + shipIdRow(ships.baseIds()));
            lines.add("ships.hidden_ids=" + shipIdRow(ships.hiddenIds()));
            lines.add("ships.over_cap=" + (ships.overCap() || ships.baseIds().size() > SHIP_ID_ROW_CAP
                    || ships.hiddenIds().size() > SHIP_ID_ROW_CAP ? 1 : 0));
            String shipLast = ships.last().isBlank() ? "-" : PeerText.bound(ships.last(), SHIP_LAST_MAX_CHARS - 1);
            lines.add("ships.last=" + shipLast);
            lines.add("ships.persist_failed=" + Math.max(0L, ships.persistFailed()));
        }
        if (magic != null) {
            // The player-ship receipt rides the serve's transform-facts
            // block (a writer with no MagicSeam - the goldens - writes neither key), read live so a step-guard
            // latch mid-match reaches the host at the next compose; right before magic_seam=.
            String pilotShip = System.getProperty(cc.starsector.server.transform.TransformRegistry.PILOT_SHIP_RECEIPT_SYSPROP);
            lines.add("pilot_ship=" + (pilotShip == null || pilotShip.isBlank() ? PILOT_SHIP_UNSET : sanitize(pilotShip)));
            String receipt = magic.receiptOrNull();
            lines.add("magic_seam=" + (receipt == null || receipt.isBlank() ? MAGIC_SEAM_UNSET : sanitize(receipt)));
        }
        lines.add("seat.count=" + seats.size());
        for (int i = 0; i < seats.size(); i++) {
            SeatRow row = seats.get(i);
            String p = "seat." + (i + 1) + ".";
            lines.add(p + "seat=" + row.seatId());
            // client= is DISPLAY text — a 16+ character cid
            // prints as its 8-character prefix (PeerText.cidForLog); the seat's full id rides only its
            // cidkey= hex below.
            lines.add(p + "client=" + sanitize(PeerText.cidForLog(row.clientId())));
            // The seat's EXACT cid key (hex of the
            // raw session cid's UTF-8; "" = a blank cid) — the GUI/web Ban confirm echoes THIS as
            // the `ban <seat> <cidkey>` tail (client= is PeerText-bounded text, never re-hexed).
            String cid = row.clientId();
            lines.add(p + "cidkey=" + (cid == null || cid.isBlank() ? ""
                    : sanitize(cc.starsector.server.runtime.BanSet.cidKey(cid))));
            lines.add(p + "name=" + sanitize(row.name())); // D3: "" = no name announced
            lines.add(p + "endpoint=" + sanitize(row.endpoint()));
            lines.add(p + "ship=" + sanitize(row.ship()));
            lines.add(p + "live=" + row.live());
            lines.add(p + "tx_bytes=" + row.txBytes());
            lines.add(p + "rx_bytes=" + row.rxBytes());
            lines.add(p + "tx_bps=" + seatBps[i][0]);
            lines.add(p + "rx_bps=" + seatBps[i][1]);
            lines.add(p + "ctrl_depth=" + row.ctrlDepth());
            // Step 3(f): the seat's FLYING custom ("" = a stock hull); hull/name are author text.
            lines.add(p + "custom=" + sanitize(row.custom()));
            lines.add(p + "hull=" + sanitize(row.customHull()));
            lines.add(p + "op=" + row.customOp());
            lines.add(p + "custom_name=" + sanitize(row.customName()));
            // Every ACCEPTED not-banned custom the seat holds (server-minted ids,
            // comma-joined, mint order; "" = none) — ban/export reach a held-but-unflown id.
            // ≤ 16 ids × ~25 chars sits under the sanitize cap.
            lines.add(p + "customs=" + sanitize(row.customs()));
            if (magic != null) {
                // The seat's MagicLib cell + its reason, judged by the mark's
                // own predicate over the mark's own inputs (the receipt, the door, the seat's HELLO bits).
                int bits = magic.helloBitsBySeat().applyAsInt(row.seatId());
                String why = MagicSeamFollow.whyOrNull(magic.receiptOrNull(), magic.doorArmed(), bits);
                lines.add(p + "magic=" + magicCell(why, bits));
                lines.add(p + "magic_why=" + (why == null ? MAGIC_NONE : why));
            }
        }
        if (bans == null) {
            return lines;
        }
        lines.add("bans=" + bans.size());
        int banRowsWritten = Math.min(bans.size(), MAX_BAN_ROWS);
        for (int i = 0; i < banRowsWritten; i++) {
            BanRow ban = bans.get(i);
            String p = "ban." + (i + 1) + ".";
            lines.add(p + "kind=" + sanitize(ban.kind()));
            lines.add(p + "key=" + sanitize(ban.key()));
            lines.add(p + "at=" + ban.atMillis());
            lines.add(p + "origin=" + sanitize(ban.origin()));
        }
        if (bans.size() > banRowsWritten) {
            lines.add("bans_more=" + (bans.size() - banRowsWritten));
        }
        return lines;
    }

    /**
     * k=v values are one per line and never quoted: every control character
     * folds to a space (was CR/LF only) and the value is capped at {@link PeerText#OPS_VALUE_MAX_CHARS}
     * with a visible marker (was unbounded). The seat rows carry peer-controlled text (clientId).
     */
    private static String sanitize(String value) {
        return PeerText.bound(value, PeerText.OPS_VALUE_MAX_CHARS);
    }

    /**
     * Seat ship derivation: seatId → ship variant name from the ONE
     * descriptor decode ({@code MatchSeatAuthority} drops the descriptor, and its
     * {@code Seat} carries no variant string). Join = the seat's side's {@code pilot=SEAT}
     * roster row; a side with zero or multiple SEAT rows is ambiguous ⇒ {@code "?"} fail-soft
     * (never a throw — this feeds a status file, not the wire).
     */
    public static Map<Integer, String> seatShipNames(MatchDescriptor descriptor) {
        Map<Integer, String> bySide = new HashMap<>(); // side -> sole SEAT-row variant
        Map<Integer, Integer> seatRowsPerSide = new HashMap<>();
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            if (row.pilot() == MatchDescriptor.PilotKind.SEAT) {
                seatRowsPerSide.merge(row.side(), 1, Integer::sum);
                bySide.put(row.side(), row.variantId());
            }
        }
        Map<Integer, String> bySeatId = new HashMap<>();
        for (MatchDescriptor.Seat seat : descriptor.seats()) {
            Integer rows = seatRowsPerSide.get(seat.side());
            bySeatId.put(seat.seatId(),
                    rows != null && rows == 1 ? bySide.get(seat.side()) : "?");
        }
        return bySeatId;
    }
}
