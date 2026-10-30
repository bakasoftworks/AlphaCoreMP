package cc.starsector.server.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed {@code ccops.v=1} snapshot — the consumer half of {@link OpsStatusFile} (the GUI's
 * 1 s poll target; round-trip gated by {@code OpsStatusContract}). Parse is fail-CLOSED on the
 * contract core (version row, every fixed key, numeric shape, complete seat blocks) so a
 * half-formed or foreign file never renders as a healthy server — the writer's atomic move
 * means a well-formed file is the only thing a reader should ever see, and anything else is a
 * caller-visible {@link IllegalArgumentException} (the GUI folds it into its unreadable state).
 * Unknown EXTRA keys are ignored (appended-fields growth stays compatible).
 */
public final class OpsSnapshot {

    private final long stampMs;
    private final long pid;
    private final String phase;
    private final String host;
    private final int port;
    private final long upSeconds;
    private final int round;
    private final int frame;
    private final long simTick;
    private final int seatsTotal;
    private final int clients;
    private final double tickPerS;
    private final double utilPct;
    private final double workMaxMs;
    private final long bytesSentTotal;
    private final long bytesRecvTotal;
    private final long sentBps;
    private final long recvBps;
    private final int overrunFrames;
    private final double debtMs; //
    private final long sendFailures;
    private final String commandFile;
    private final List<SeatRow> seats;
    private final long phaseFrames; // scale-lane row 2
    private final List<PhaseStat> phases;
    // W15 v0 step 3(f): OPTIONAL custom.* keys (a pre-3(f) writer's file still parses: 0/0/0/off).
    private final int customCount;
    private final int customRejected;
    private final int customBanned;
    private final boolean customSubmissions;
    // OPTIONAL rejected split / pending + cap / policy knobs / export readback
    // (a pre-(e) writer's file still parses: zeros, "-" for the knob strings).
    private final int customRejectedPolicy;
    private final int customRejectedCaps;
    private final int customRejectedEngine;
    private final int customRejectedOperator;
    // The R3b refusal meters (custom.frame_encode_failed / custom.vsub_decode_refused),
    // the same optional-key law (a pre-R3b writer parses as 0).
    private final int customFrameEncodeFailed;
    private final int customVsubDecodeRefused;
    private final int customPending;
    private final int customCapGlobal;
    private final String customTier;
    private final int customPerSeat;
    private final String customSModCap;
    private final String customDMods;
    private final long customExported;
    private final long customExportFailed;
    /** The OPTIONAL kept-store rows {@code OpsStatusFile} writes
     *  since the kept store landed - {@code custom.kept=}, {@code custom.kept_ids=},
     *  {@code custom.kept_failed=}, {@code custom.kept_last=}, {@code custom.inbox_ids=}, {@code custom.kept_over_cap=},
     *  {@code custom.inbox_over_cap=}. A pre-kept writer's file still parses: 0 / empty / {@code "-"} / false,
     *  and {@link #customKeptKnown} false (the customs detail text then stays byte-identical). */
    private final boolean customKeptKnown;
    private final int customKept;
    private final List<String> customKeptIds;
    private final long customKeptFailed;
    private final String customKeptLast;
    private final List<String> customInboxIds;
    private final boolean customKeptOverCap;
    private final boolean customInboxOverCap;
    /** The OPTIONAL shipped-ships rows {@code OpsStatusFile} writes for the
     *  host's Hide / Show list - {@code ships.base_ids=} (the ids that come with the server, at most the writer's 64,
     *  hidden ones included), {@code ships.hidden_ids=}, {@code ships.over_cap=} (0/1), {@code ships.last=} (the last
     *  hide / show verdict, sanitized by the writer) and {@code ships.persist_failed=} (saves of the hidden list that
     *  failed). A pre-ships writer's file still parses: false / empty / {@code "-"} / 0, and {@link #shipsKnown} false. */
    private final boolean shipsKnown;
    private final List<String> shipsBaseIds;
    private final List<String> shipsHiddenIds;
    private final boolean shipsOverCap;
    private final String shipsLast;
    private final long shipsPersistFailed;
    // OPTIONAL P27 ban block (a pre-P27 writer: 0 / empty).
    private final int bans;
    private final int bansMore;
    private final List<BanRow> banRows;

    /** One parsed {@code ban.<i>.*} status row —
     *  {@code index} = the writer's 1-based {@code i}, {@code kind} = {@code cid|src},
     *  {@code key} = the BanSet key form (hex / IP literal), {@code origin} = {@code op|admin}. */
    public record BanRow(int index, String kind, String key, long atMillis, String origin) {
    }

    /** One parsed seat block (bps twins are the file's window values, not recomputed).
     * {@code custom/customHull/customOp/customName} = the step 3(f) OPTIONAL keys
     * ({@code ""}/{@code -1} when the writer predates them); {@code customs} = the
     * OPTIONAL comma-joined accepted-id list ({@code ""} when absent); {@code cidKey} = the
     * OPTIONAL {@code seat.N.cidkey} ({@code ""} for an old writer / a blank
     * cid) — the key the GUI/web ban lines carry. */
    public record SeatRow(int seatId, String clientId, String endpoint, String ship,
            boolean live, long txBytes, long rxBytes, long txBps, long rxBps, int ctrlDepth,
            String name, String custom, String customHull, int customOp, String customName,
            String customs, String cidKey, String magic, String magicWhy) {
        /** The pre-MagicLib arity - {@code magic} / {@code magicWhy} read
         *  {@code ""} (the writer said nothing about MagicLib for this seat). */
        public SeatRow(int seatId, String clientId, String endpoint, String ship,
                boolean live, long txBytes, long rxBytes, long txBps, long rxBps, int ctrlDepth,
                String name, String custom, String customHull, int customOp, String customName,
                String customs, String cidKey) {
            this(seatId, clientId, endpoint, ship, live, txBytes, rxBytes, txBps, rxBps,
                    ctrlDepth, name, custom, customHull, customOp, customName, customs, cidKey, "", "");
        }

        /** The pre-P27-wave-3 arity (no cid key). */
        public SeatRow(int seatId, String clientId, String endpoint, String ship,
                boolean live, long txBytes, long rxBytes, long txBps, long rxBps, int ctrlDepth,
                String name, String custom, String customHull, int customOp, String customName,
                String customs) {
            this(seatId, clientId, endpoint, ship, live, txBytes, rxBytes, txBps, rxBps,
                    ctrlDepth, name, custom, customHull, customOp, customName, customs, "");
        }

        /** The older arity (no accepted-id list). */
        public SeatRow(int seatId, String clientId, String endpoint, String ship,
                boolean live, long txBytes, long rxBytes, long txBps, long rxBps, int ctrlDepth,
                String name, String custom, String customHull, int customOp, String customName) {
            this(seatId, clientId, endpoint, ship, live, txBytes, rxBytes, txBps, rxBps,
                    ctrlDepth, name, custom, customHull, customOp, customName, "");
        }

        /** The pre-step-3(f) arity (no custom block). */
        public SeatRow(int seatId, String clientId, String endpoint, String ship,
                boolean live, long txBytes, long rxBytes, long txBps, long rxBps, int ctrlDepth,
                String name) {
            this(seatId, clientId, endpoint, ship, live, txBytes, rxBytes, txBps, rxBps,
                    ctrlDepth, name, "", "", -1, "");
        }

        /** The pre-D3 arity (no announced name) — the selftests' fakes. */
        public SeatRow(int seatId, String clientId, String endpoint, String ship,
                boolean live, long txBytes, long rxBytes, long txBps, long rxBps, int ctrlDepth) {
            this(seatId, clientId, endpoint, ship, live, txBytes, rxBytes, txBps, rxBps,
                    ctrlDepth, "");
        }
    }

    /** One parsed per-phase window row (scale-lane row 2; order = the writer's bracket order). */
    public record PhaseStat(String name, double meanMs, double p95Ms) {
    }

    /**
     * The console's last reachability self-dial ({@code ServerOpsCore.checkReach} / the {@code cc-reach-check} daemon):
     * {@code verdict} = the stdout token {@code reached|local|silent|refused}, {@code ms} = the dial's elapsed wall
     * time (>= 0), {@code note} = the host-facing sentence ({@code ReachProbe.note}; holds the host's own Advertise),
     * {@code atMs} = the console's wall clock when the dial ended (epoch ms, > 0). WHY on the snapshot but never in
     * the status file: the serve writes that file and never dials; the dial is the console's, so a console attaches
     * its result to the snapshot it just parsed ({@link #withReach}) and both shells REPORT {@link #reach()}.
     *
     *
     */
    public record Reach(String verdict, long ms, String note, long atMs) {
        public Reach {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(note, "note");
        }
    }

    /** The parsed rows, kept so {@link #withReach} re-builds an equal snapshot. */
    private final Map<String, String> kv;
    private final List<BanRow> banRowsIn;
    /** Null = no dial attached (every parsed snapshot: the serve never writes one). */
    private final Reach reach;

    private OpsSnapshot(Map<String, String> kv, List<SeatRow> seats, List<BanRow> banRows) {
        this(kv, seats, banRows, null);
    }

    private OpsSnapshot(Map<String, String> kv, List<SeatRow> seats, List<BanRow> banRows, Reach reach) {
        this.kv = Map.copyOf(kv);
        this.banRowsIn = List.copyOf(banRows);
        this.reach = reach;
        this.stampMs = longOf(kv, "stamp_ms");
        this.pid = longOf(kv, "pid");
        this.phase = stringOf(kv, "phase");
        this.host = stringOf(kv, "host");
        this.port = intOf(kv, "port");
        this.upSeconds = longOf(kv, "up_s");
        this.round = intOf(kv, "round");
        this.frame = intOf(kv, "frame");
        this.simTick = longOf(kv, "sim_tick");
        this.seatsTotal = intOf(kv, "seats_total");
        this.clients = intOf(kv, "clients");
        this.tickPerS = doubleOf(kv, "tick_per_s");
        this.utilPct = doubleOf(kv, "util_pct");
        this.workMaxMs = doubleOf(kv, "work_max_ms");
        this.bytesSentTotal = longOf(kv, "bytes_sent_total");
        this.bytesRecvTotal = longOf(kv, "bytes_recv_total");
        this.sentBps = longOf(kv, "sent_bps");
        this.recvBps = longOf(kv, "recv_bps");
        this.overrunFrames = intOf(kv, "overrun_frames");
        this.debtMs = doubleOf(kv, "debt_ms");
        this.sendFailures = longOf(kv, "send_failures");
        this.commandFile = stringOf(kv, "command_file");
        // Step 3(f): optional-key idiom (the D3 `name` precedent) — never a version bump.
        this.customCount = optionalInt(kv, "custom.count");
        this.customRejected = optionalInt(kv, "custom.rejected");
        this.customBanned = optionalInt(kv, "custom.banned");
        this.customSubmissions = "on".equals(kv.getOrDefault("custom.submissions", "off"));
        // The same optional-key law for the split / cap / knob / export rows.
        this.customRejectedPolicy = optionalInt(kv, "custom.rejected.policy");
        this.customRejectedCaps = optionalInt(kv, "custom.rejected.caps");
        this.customRejectedEngine = optionalInt(kv, "custom.rejected.engine");
        this.customRejectedOperator = optionalInt(kv, "custom.rejected.operator");
        this.customFrameEncodeFailed = optionalInt(kv, "custom.frame_encode_failed");
        this.customVsubDecodeRefused = optionalInt(kv, "custom.vsub_decode_refused");
        this.customPending = optionalInt(kv, "custom.pending");
        this.customCapGlobal = optionalInt(kv, "custom.cap_global");
        this.customTier = kv.getOrDefault("custom.tier", "-");
        this.customPerSeat = optionalInt(kv, "custom.per_seat");
        this.customSModCap = kv.getOrDefault("custom.smod_cap", "-");
        this.customDMods = kv.getOrDefault("custom.dmods", "-");
        this.customExported = kv.containsKey("custom.exported") ? longOf(kv, "custom.exported") : 0L;
        this.customExportFailed = kv.containsKey("custom.export_failed")
                ? longOf(kv, "custom.export_failed") : 0L;
        // The kept rows under the same optional-key law (a present
        // but malformed number still fails closed); ids as the writer wrote them (sanitized there).
        this.customKeptKnown = kv.containsKey("custom.kept");
        this.customKept = optionalInt(kv, "custom.kept");
        this.customKeptIds = idRow(kv.get("custom.kept_ids"));
        this.customKeptFailed = kv.containsKey("custom.kept_failed") ? longOf(kv, "custom.kept_failed") : 0L;
        String keptLast = kv.get("custom.kept_last");
        this.customKeptLast = keptLast == null || keptLast.isBlank() ? "-" : keptLast;
        this.customInboxIds = idRow(kv.get("custom.inbox_ids"));
        this.customKeptOverCap = "1".equals(kv.get("custom.kept_over_cap"));
        this.customInboxOverCap = "1".equals(kv.get("custom.inbox_over_cap"));
        // The shipped-ships rows under the same optional-key law (a present
        // but malformed counter still fails closed); ids as the writer wrote them (sanitized there).
        this.shipsKnown = kv.containsKey("ships.base_ids");
        this.shipsBaseIds = idRow(kv.get("ships.base_ids"));
        this.shipsHiddenIds = idRow(kv.get("ships.hidden_ids"));
        this.shipsOverCap = "1".equals(kv.get("ships.over_cap"));
        String shipsLastRow = kv.get("ships.last");
        this.shipsLast = shipsLastRow == null || shipsLastRow.isBlank() ? "-" : shipsLastRow;
        this.shipsPersistFailed = kv.containsKey("ships.persist_failed") ? longOf(kv, "ships.persist_failed") : 0L;
        this.seats = List.copyOf(seats);
        this.bans = optionalInt(kv, "bans");
        // F12 clamped >= 0 (a hand-edited / degenerate
        // file never paints a negative "more not listed" tail).
        this.bansMore = Math.max(0, optionalInt(kv, "bans_more"));
        this.banRows = List.copyOf(banRows);
        this.phaseFrames = longOf(kv, "ph.frames");
        List<PhaseStat> phaseRows = new ArrayList<>(PhaseWindowStats.PHASES);
        for (String name : PhaseWindowStats.PHASE_NAMES) {
            phaseRows.add(new PhaseStat(name,
                    doubleOf(kv, "ph." + name + ".mean_ms"),
                    doubleOf(kv, "ph." + name + ".p95_ms")));
        }
        this.phases = List.copyOf(phaseRows);
    }

    /** The console's reachability result attached by {@link #withReach}; null = none (see {@link Reach}). */
    public Reach reach() {
        return reach;
    }

    /**
     * This snapshot with {@code r} attached (null = detached): every parsed field equal, only {@link #reach()}
     * differs.
     */
    public OpsSnapshot withReach(Reach r) {
        return r == reach ? this : new OpsSnapshot(kv, seats, banRowsIn, r);
    }

    /** Read + parse the writer's file. IO problems surface as {@link IOException}. */
    public static OpsSnapshot parseFile(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException on version/key/shape violations (fail-closed core). */
    public static OpsSnapshot parse(List<String> lines) {
        Map<String, String> kv = new HashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("ops snapshot line is not k=v: " + line);
            }
            kv.put(line.substring(0, eq), line.substring(eq + 1));
        }
        String version = kv.get("ccops.v");
        if (version == null) {
            throw new IllegalArgumentException(
                    "not an ops snapshot: no ccops.v row in " + lines.size() + " line(s)");
        }
        if (!String.valueOf(OpsStatusFile.VERSION).equals(version)) {
            throw new IllegalArgumentException("unsupported ops snapshot version ccops.v="
                    + version + " (this reader speaks v" + OpsStatusFile.VERSION + ")");
        }
        int seatCount = intOf(kv, "seat.count");
        if (seatCount < 0) {
            throw new IllegalArgumentException("seat.count must be >= 0, got " + seatCount);
        }
        List<SeatRow> seats = new ArrayList<>(seatCount);
        for (int i = 1; i <= seatCount; i++) {
            String p = "seat." + i + ".";
            seats.add(new SeatRow(
                    intOf(kv, p + "seat"),
                    stringOf(kv, p + "client"),
                    stringOf(kv, p + "endpoint"),
                    stringOf(kv, p + "ship"),
                    boolOf(kv, p + "live"),
                    longOf(kv, p + "tx_bytes"),
                    longOf(kv, p + "rx_bytes"),
                    longOf(kv, p + "tx_bps"),
                    longOf(kv, p + "rx_bps"),
                    intOf(kv, p + "ctrl_depth"),
                    // OPTIONAL key (a pre-D3 writer's file still parses —
                    // the GUI may outlive the serve that wrote it); "" = none announced.
                    kv.getOrDefault(p + "name", ""),
                    // W15 v0 step 3(f): the OPTIONAL custom block (same law).
                    kv.getOrDefault(p + "custom", ""),
                    kv.getOrDefault(p + "hull", ""),
                    optionalInt(kv, p + "op", -1),
                    kv.getOrDefault(p + "custom_name", ""),
                    // The OPTIONAL accepted-id list (same law).
                    kv.getOrDefault(p + "customs", ""),
                    // The OPTIONAL cid key (same law).
                    kv.getOrDefault(p + "cidkey", ""),
                    // The OPTIONAL MagicLib cell + its reason ("" = absent).
                    magicToken(kv, p + "magic", MAGIC_CELLS),
                    magicToken(kv, p + "magic_why", MAGIC_WHYS)));
        }
        return new OpsSnapshot(kv, seats, parseBanRows(kv));
    }

    /** The OPTIONAL P27 ban block — absent
     *  {@code bans=} (a pre-P27 writer) = no rows; else {@code min(bans, MAX_BAN_ROWS)} complete
     *  {@code ban.<i>.kind/key/at/origin} blocks, fail-closed like the seat blocks (a present
     *  count with a missing / malformed row is a half-formed file). Never more than the writer's
     *  cap: the rest is the {@code bans_more=} count. */
    private static List<BanRow> parseBanRows(Map<String, String> kv) {
        if (!kv.containsKey("bans")) {
            return List.of();
        }
        int count = intOf(kv, "bans");
        if (count < 0) {
            throw new IllegalArgumentException("bans must be >= 0, got " + count);
        }
        int rows = Math.min(count, OpsStatusFile.MAX_BAN_ROWS);
        List<BanRow> out = new ArrayList<>(rows);
        for (int i = 1; i <= rows; i++) {
            String p = "ban." + i + ".";
            out.add(new BanRow(i, stringOf(kv, p + "kind"), stringOf(kv, p + "key"),
                    longOf(kv, p + "at"), stringOf(kv, p + "origin")));
        }
        return out;
    }

    /** P27: the server's total ban count ({@code bans=}; 0 when the writer predates it). */
    public int bans() {
        return bans;
    }

    /** P27: the rows past the writer's {@link OpsStatusFile#MAX_BAN_ROWS} cap ({@code bans_more=}). */
    public int bansMore() {
        return bansMore;
    }

    /** P27: the carried {@code ban.<i>} rows (empty for a pre-P27 writer or {@code bans=0}). */
    public List<BanRow> banRows() {
        return banRows;
    }

    /** The {@code seat.N.magic=} words the writer emits. */
    private static final java.util.Set<String> MAGIC_CELLS = java.util.Set.of(OpsStatusFile.MAGIC_FOLLOWS,
            OpsStatusFile.MAGIC_REPORTS, OpsStatusFile.MAGIC_NONE);
    /** The {@code seat.N.magic_why=} words the writer emits (the predicate's reasons, {@code -} when followed). */
    private static final java.util.Set<String> MAGIC_WHYS = java.util.Set.of(OpsStatusFile.MAGIC_NONE,
            cc.starsector.server.runtime.MagicSeamFollow.WHY_HOST_OFF,
            cc.starsector.server.runtime.MagicSeamFollow.WHY_NOT_APPLIED,
            cc.starsector.server.runtime.MagicSeamFollow.WHY_DOOR_UNARMED,
            cc.starsector.server.runtime.MagicSeamFollow.WHY_NO_UP);

    /** An OPTIONAL MagicLib seat word - absent = {@code ""}; a present word
     *  outside {@code allowed} fails closed (the optional-key law: a malformed value is a half-formed file). */
    private static String magicToken(Map<String, String> kv, String key, java.util.Set<String> allowed) {
        String v = kv.get(key);
        if (v == null) {
            return "";
        }
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(key + " is not one of " + new java.util.TreeSet<>(allowed) + ": "
                    + PeerText.bound(v, 32));
        }
        return v;
    }

    /** {@code magic_seam=} - the serve's MagicLib seam receipt as written
     *  ({@code off} | {@code armed} | {@code applied:<hex>} | {@code disarmed:<reason>} | {@code unset} = no
     *  receipt), or {@code ""} when the writer carries no seam rows (a pre-seam serve). */
    public String magicSeam() {
        return kv.getOrDefault("magic_seam", "");
    }

    /** {@code pilot_ship=} - the serve's player-ship receipt as written
     *  ({@code off} | {@code armed} | {@code applied:<classes>} | {@code refused:<reason>} | {@code latched:<reason>}
     *  | {@code unset} = no receipt), or {@code ""} when the writer carries no such row (an older serve). Read
     *  verbatim, the {@link #magicSeam} way: only the MATCH line words it. */
    public String pilotShip() {
        return kv.getOrDefault("pilot_ship", "");
    }

    /** An OPTIONAL int key: absent = {@code fallback}; present-but-malformed still fails closed. */
    private static int optionalInt(Map<String, String> kv, String key, int fallback) {
        return kv.containsKey(key) ? intOf(kv, key) : fallback;
    }

    private static int optionalInt(Map<String, String> kv, String key) {
        return optionalInt(kv, key, 0);
    }

    public int customCount() {
        return customCount;
    }

    public int customRejected() {
        return customRejected;
    }

    public int customBanned() {
        return customBanned;
    }

    public boolean customSubmissions() {
        return customSubmissions;
    }

    // The optional split / cap / knob / export rows (zeros, "-" when absent).

    public int customRejectedPolicy() {
        return customRejectedPolicy;
    }

    public int customRejectedCaps() {
        return customRejectedCaps;
    }

    public int customRejectedEngine() {
        return customRejectedEngine;
    }

    public int customRejectedOperator() {
        return customRejectedOperator;
    }

    /** {@code custom.frame_encode_failed=}: submit frames the reader thread's canonical re-encode refused (0 when absent). */
    public int customFrameEncodeFailed() {
        return customFrameEncodeFailed;
    }

    /** {@code custom.vsub_decode_refused=}: recorded frames the re-sim's decode refused (0 when absent). */
    public int customVsubDecodeRefused() {
        return customVsubDecodeRefused;
    }

    public int customPending() {
        return customPending;
    }

    /** 0 = the writer predates the key (no headroom math possible). */
    public int customCapGlobal() {
        return customCapGlobal;
    }

    public String customTier() {
        return customTier;
    }

    public int customPerSeat() {
        return customPerSeat;
    }

    public String customSModCap() {
        return customSModCap;
    }

    public String customDMods() {
        return customDMods;
    }

    public long customExported() {
        return customExported;
    }

    public long customExportFailed() {
        return customExportFailed;
    }

    /** One comma-joined id row ({@code OpsStatusFile.keptIdRow}):
     *  absent / blank / {@code -} = empty; else each non-empty piece as written. */
    private static List<String> idRow(String row) {
        if (row == null || row.isBlank() || "-".equals(row)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String id : row.split(",")) {
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    /** true when the writer carries the kept rows ({@code custom.kept=} present). */
    public boolean customKeptKnown() {
        return customKeptKnown;
    }

    /** {@code custom.kept=}: ships kept for every player (0 when absent). */
    public int customKept() {
        return customKept;
    }

    /** {@code custom.kept_ids=}: the kept stems (at most the writer's 32; empty when absent or {@code -}). */
    public List<String> customKeptIds() {
        return customKeptIds;
    }

    /** {@code custom.kept_failed=}: kept verbs refused + kept jobs the writer refused or failed. */
    public long customKeptFailed() {
        return customKeptFailed;
    }

    /** {@code custom.kept_last=}: the writer's last kept verdict ({@code -} when none / absent). */
    public String customKeptLast() {
        return customKeptLast;
    }

    /** {@code custom.inbox_ids=}: inbox files not kept yet (at most the writer's 64 = {@code OpsStatusFile.INBOX_ID_ROW_CAP},
     *  the {@code kept-…} names first; empty when absent or {@code -}). */
    public List<String> customInboxIds() {
        return customInboxIds;
    }

    /** {@code custom.kept_over_cap=1}: the kept folder holds more than the listed ids. */
    public boolean customKeptOverCap() {
        return customKeptOverCap;
    }

    /** {@code custom.inbox_over_cap=1}: the inbox holds more names than the listed ones. */
    public boolean customInboxOverCap() {
        return customInboxOverCap;
    }

    /** true when the writer carries the shipped-ships rows
     *  ({@code ships.base_ids=} present). */
    public boolean shipsKnown() {
        return shipsKnown;
    }

    /** {@code ships.base_ids=}: the ids that come with the server, hidden ones included (at most the writer's 64;
     *  empty when absent or {@code -}). */
    public List<String> shipsBaseIds() {
        return shipsBaseIds;
    }

    /** {@code ships.hidden_ids=}: the ids the host hid from every player (empty when absent or {@code -}). */
    public List<String> shipsHiddenIds() {
        return shipsHiddenIds;
    }

    /** {@code ships.over_cap=1}: the server has more shipped ids than the listed ones. */
    public boolean shipsOverCap() {
        return shipsOverCap;
    }

    /** {@code ships.last=}: the last hide / show verdict ({@code -} when none / absent). */
    public String shipsLast() {
        return shipsLast;
    }

    /** {@code ships.persist_failed=}: saves of the hidden list that failed (0 when absent). */
    public long shipsPersistFailed() {
        return shipsPersistFailed;
    }

    /** The GUI's STALE test: the writer stamps every rewrite; silence past the threshold. */
    public boolean isStale(long nowMs, long staleThresholdMs) {
        return nowMs - stampMs > staleThresholdMs;
    }

    public long stampMs() {
        return stampMs;
    }

    public long pid() {
        return pid;
    }

    public String phase() {
        return phase;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public long upSeconds() {
        return upSeconds;
    }

    public int round() {
        return round;
    }

    public int frame() {
        return frame;
    }

    public long simTick() {
        return simTick;
    }

    public int seatsTotal() {
        return seatsTotal;
    }

    public int clients() {
        return clients;
    }

    public double tickPerS() {
        return tickPerS;
    }

    public double utilPct() {
        return utilPct;
    }

    public double workMaxMs() {
        return workMaxMs;
    }

    public long bytesSentTotal() {
        return bytesSentTotal;
    }

    public long bytesRecvTotal() {
        return bytesRecvTotal;
    }

    public long sentBps() {
        return sentBps;
    }

    public long recvBps() {
        return recvBps;
    }

    public int overrunFrames() {
        return overrunFrames;
    }

    /** Current pacer debt (ms) at the last fed frame; 0 = on schedule. */
    public double debtMs() {
        return debtMs;
    }

    public long sendFailures() {
        return sendFailures;
    }

    public String commandFile() {
        return commandFile;
    }

    public List<SeatRow> seats() {
        return seats;
    }

    /** Frames fed into the phase window this write covers (scale-lane row 2). */
    public long phaseFrames() {
        return phaseFrames;
    }

    /** Per-phase window mean/p95 (ms), writer bracket order pre..tail (scale-lane row 2). */
    public List<PhaseStat> phases() {
        return phases;
    }

    private static String stringOf(Map<String, String> kv, String key) {
        String value = kv.get(key);
        if (value == null) {
            throw new IllegalArgumentException("ops snapshot missing required key: " + key);
        }
        return value;
    }

    private static long longOf(Map<String, String> kv, String key) {
        try {
            return Long.parseLong(stringOf(kv, key));
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException(
                    "ops snapshot key " + key + " is not an integer: " + kv.get(key));
        }
    }

    private static int intOf(Map<String, String> kv, String key) {
        long value = longOf(kv, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "ops snapshot key " + key + " overflows int: " + value);
        }
        return (int) value;
    }

    private static double doubleOf(Map<String, String> kv, String key) {
        try {
            return Double.parseDouble(stringOf(kv, key));
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException(
                    "ops snapshot key " + key + " is not a number: " + kv.get(key));
        }
    }

    private static boolean boolOf(Map<String, String> kv, String key) {
        String value = stringOf(kv, key);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                "ops snapshot key " + key + " is not true/false: " + value);
    }
}
