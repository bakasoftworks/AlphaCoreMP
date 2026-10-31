package cc.starsector.server.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The both-direction WIRE RECORD SINK (format {@code CCWR}, v1 pre-registered
 * BEFORE this class was built; v2 adds the row
 * kinds and the END trailer).
 *
 * <p>Persists what actually CROSSED a {@link PilotIntentSocketServer} wire, one append-only file
 * per endpoint: each DOWN record holds the exact multiplexed bytes one exchange wrote (0..n
 * {@code GPEE} frames + 1 {@code AWSS} frame on the predict lane, or 1 {@code PWVW} frame on the
 * worldview lane — demuxed by their own inner magic, exactly like the client); each UP record holds
 * the received {@link PilotIntentFrame} re-encoded through the unchanged fixed-width+CRC
 * {@link PilotIntentWireCodec} (the same re-encode identity the gate's digest accounting relies on).
 * DOWN/UP payload frames keep their own magic + schema version; every row-kind payload
 * ({@value #KIND_ROW_MIN}..{@value #KIND_ROW_MAX}) leads with its OWN u32 magic
 * ({@link #rowMagicOrZero}) + a row-version byte, so the container still adds no second schema
 * authority — a reader routes a row by its kind and refuses one whose magic disagrees. Consumers:
 *WATCH forensics, regression fixtures, the replay server (which re-speaks DOWN
 * payloads verbatim — zero client change), the re-sim (which consumes the row kinds).
 *
 * <p>Layout (big-endian, {@link DataOutputStream} conventions like every codec):
 * header = u32 magic {@code 0x43435752} ("CCWR") · u16 format version · s64 created epoch-millis ·
 * u16 meta count · count × (UTF key, UTF value); record = u8 kind (1=DOWN, 2=UP,
 * {@value #KIND_ROW_MIN}..{@value #KIND_ROW_MAX} = the row kinds, {@value #KIND_END} = END) · s64
 * server tick (gate-stamped; -1 = pre-combat-loop traffic such as keep-warm exchanges) · s64 record
 * epoch-millis · s32 payload length · payload. Every record is flushed — a crashed WATCH leaves a
 * readable file, and {@link #verify} tolerates a truncated final record. Version 2 = version 1 plus
 * the row kinds and the END trailer; {@link #verify} reads 1..{@value #FORMAT_VERSION} and refuses
 * any other version at the header.
 *
 * <p>The END trailer (kind {@value #KIND_END}) is written ONLY by {@link #closeWithEnd} (an orderly
 * serve teardown) and by the byte cap; it carries the sink's own counters, so a reader that finds
 * no END knows the record was cut short (a crash, a dead disk) or closed by a plain {@link #close},
 * which writes nothing — every other writer's files stay byte-identical to version 1's shape.
 *
 * <p>UP records carry no sender seat: the datagram door records only frames whose shipId passed the
 * session pin; pinned drops are counted, never recorded.
 * A reel recorded before the pin landed may hold pinned frames: a reel is post-fix iff its meta
 * {@code git.head} ({@link MatchRecordMeta}) descends from the pin's fix commit; otherwise trust its per-ship
 * UP attribution only when EVERY seat has a {@code session_dead} row showing {@code pinned_*=0} (a seat
 * alive at serve end prints none).
 *
 * <p>{@link #record} NEVER throws: recording is an instrument and must not abort the exchange it
 * observes (the edge lane's fail-open-to-floor precedent) — a disk failure latches {@link #error()},
 * disables the sink and prints ONE {@code wire_sink_dead} stdout row (the latch fires once by
 * construction: the sink is closed under the same monitor), so a dead record is never silent.
 *
 * <p>DECOUPLED mode ({@code open(path, meta, true)}): {@link #record} frames
 * the record (byte-identical layout, millis stamped at enqueue) and hands it to a
 * {@link BoundedChunkDrain} writer thread instead of writing inline, so a disk stall never blocks
 * the serve/reader thread (the HDD stall mechanism: 312 ms sync worst vs 0.24 ms
 * enqueue). Never-drop/never-reorder law incl. the queue-full fail-open drop-to-sync =
 * BoundedChunkDrain; flush is per drained batch, so a crashed WATCH can lose at most the queued
 * tail — {@link #verify}'s truncatedTail tolerance unchanged.
 */
public final class WireRecordSink implements AutoCloseable {

    public static final int MAGIC = 0x43435752; // "CCWR"
    public static final short FORMAT_VERSION = 2;
    public static final byte KIND_DOWN = 1;
    public static final byte KIND_UP = 2;

    /**
     * The row-kind band (both ends inclusive): every kind in it is a self-describing row whose
     * payload leads with the kind's own u32 magic ({@link #rowMagicOrZero}), so a reader accepts a
     * kind it has no codec for by that magic alone and a future row needs no format bump; a kind
     * outside the band and not DOWN/UP is refused.
     */
    public static final int KIND_ROW_MIN = 3;
    public static final int KIND_ROW_MAX = 31;
    /** The END trailer kind (inside the row band; magic "CCEN"); written only by the sink itself. */
    public static final byte KIND_END = 17;
    /** END trailer row version (the byte after its magic). */
    static final int END_ROW_VERSION = 1;
    /** A written string is cut to this many modified-UTF-8 bytes, never thrown on. */
    static final int END_UTF_MAX_BYTES = 255;

    /**
     * Row magic by kind (index = kind, 0 = no such kind): the row kinds' leading u32, ASCII
     * {@code CC} + two letters. Reserved kinds (no writer yet) are INCLUDED, so a record written by a
     * later writer still verifies. Package-private: the array is mutable, so out-of-package readers
     * use {@link #rowMagicOrZero}.
     */
    static final int[] ROW_MAGICS = rowMagics();

    private static int[] rowMagics() {
        int[] magics = new int[KIND_ROW_MAX + 1];
        magics[3] = 0x4343544B;  // CCTK takes
        magics[4] = 0x43435345;  // CCSE session live/dead
        magics[5] = 0x4343434D;  // CCCM operator command
        magics[6] = 0x43434649;  // CCFI fly-in
        magics[7] = 0x43435355;  // CCSU suicide
        magics[8] = 0x43435653;  // CCVS variant submit
        magics[9] = 0x43435052;  // CCPR catalog promotion (reserved)
        magics[10] = 0x43434653; // CCFS fighter spawn
        magics[11] = 0x43435750; // CCWP round wrap
        magics[12] = 0x4343524F; // CCRO live roster ratio
        magics[13] = 0x43435641; // CCVA vacate (reserved)
        magics[14] = 0x43435452; // CCTR transfer (reserved)
        magics[15] = 0x43434D4F; // CCMO module claim (reserved)
        magics[16] = 0x43434752; // CCGR grace (reserved)
        magics[KIND_END] = 0x4343454E; // CCEN end trailer
        return magics;
    }

    /** The leading u32 magic a row of {@code kind} must carry; 0 = not a known row kind. */
    public static int rowMagicOrZero(int kind) {
        return kind >= KIND_ROW_MIN && kind <= KIND_ROW_MAX ? ROW_MAGICS[kind] : 0;
    }

    /**
     * The decoupled drain's queue bound, in records (chunks). Record mode sizes its seat ADVISORY
     * against it ({@link #recordModeSeatBound}).
     */
    public static final int DECOUPLED_QUEUE_CHUNKS = 8192;
    /** The smallest legal positive byte cap in MiB: below it a normal flight's record is truncated. */
    public static final int SINK_CAP_FLOOR_MIB = 1024;
    /** The worst measured HDD write stall the drain queue must absorb, in milliseconds. */
    static final int WORST_STALL_MILLIS = 312;

    /** Per-record framing bytes (kind u8 + tick s64 + millis s64 + length s32) — the cap counts them. */
    static final int RECORD_FRAMING_BYTES = 1 + 8 + 8 + 4;
    /** No-clobber suffix search bound ({@code stem-2.ccwr}..{@code stem-99.ccwr}); past it, open THROWS. */
    static final int NO_CLOBBER_MAX_SUFFIX = 99;

    private final String path;
    private final DataOutputStream out;
    private final BoundedChunkDrain drain; // null = the original inline write+flush mode
    /**
     * Byte CAP for the public-serve shape — 0 = unbounded (every
     * watch/rig lane, byte-identical to the uncapped sink). UP records are wire-derived bytes, so an
     * unbounded public recording is a disk allocation a hostile flooder controls (security
     * bounded-allocation law); at the cap the sink closes CLEANLY (header + every
     * record so far intact), prints one {@code wire_sink_capped} row and every later record is
     * the post-close no-op — the FIRST N GiB of the flight stay replayable, never a torn file.
     */
    private final long maxBytes;
    private long bytesFramed; // header excluded; framing + payload of every record accepted
    private volatile long currentTick = -1L;
    private volatile boolean closed;
    private volatile String error = "";
    private long recordsDown, recordsUp, bytesDown, bytesUp;
    /** Every non-DOWN/UP record (the row kinds, the END trailer included) and their payload bytes. */
    private long recordsRows, bytesRows;
    /** The tick stamp of the last accepted record whose stamp was >= 0; -1 = none yet. */
    private long lastStampedTick = -1L;
    private final java.util.concurrent.atomic.LongAdder ioNanos =
            new java.util.concurrent.atomic.LongAdder();

    private WireRecordSink(String path, DataOutputStream out, BoundedChunkDrain drain, long maxBytes) {
        this.path = path;
        this.out = out;
        this.drain = drain;
        this.maxBytes = maxBytes;
    }

    /**
     * The PUBLIC-serve open ({@code run-ops-serve.ps1} default): {@code maxBytes}
     * cap (0 = unbounded) + NO-CLOBBER — a supervised relaunch (life-2) reuses the same command
     * line, so an existing non-empty file at {@code path} is never truncated: the sink opens
     * {@code stem-2.ccwr}, {@code stem-3.ccwr}, … instead ({@link #path()} = the file actually
     * opened; the serve prints it). Watch/rig lanes keep {@link #open(String, Map, boolean)}
     * (unique stamped paths; a rig that re-runs into one dir expects the overwrite).
     */
    public static WireRecordSink openBounded(String path, Map<String, String> meta, boolean decoupled,
            long maxBytes) throws IOException {
        Objects.requireNonNull(path, "path");
        Path p = Path.of(path);
        String stem = p.getFileName().toString();
        boolean ccwr = stem.endsWith(".ccwr");
        String base = ccwr ? stem.substring(0, stem.length() - ".ccwr".length()) : stem;
        Path chosen = p;
        for (int n = 2; Files.exists(chosen) && sizeOrZero(chosen) > 0; n++) {
            if (n > NO_CLOBBER_MAX_SUFFIX) {
                throw new IOException("no-clobber exhausted: " + NO_CLOBBER_MAX_SUFFIX
                        + " non-empty siblings of " + path);
            }
            String name = base + "-" + n + (ccwr ? ".ccwr" : "");
            chosen = p.getParent() == null ? Path.of(name) : p.getParent().resolve(name);
        }
        return open(chosen.toString(), meta, decoupled, DECOUPLED_QUEUE_CHUNKS, Math.max(0L, maxBytes));
    }

    /**
     * The record-mode byte-cap refusal: a positive cap below {@value #SINK_CAP_FLOOR_MIB} MiB is
     * refused at serve resolve (a five-minute one-seat flight already records 265-500 MB, so such a
     * cap truncates the very flight it should prove); 0 (unbounded) and every cap at or above the
     * floor pass. Returns the refusal text, or null.
     */
    public static String refuseSinkCapOrNull(int mib) {
        if (mib > 0 && mib < SINK_CAP_FLOOR_MIB) {
            return "cc.net.sinkMaxMiB=" + mib + " is below the record floor of " + SINK_CAP_FLOOR_MIB
                    + " MiB (a five-minute flight records 265-500 MB, so this cap truncates it); set "
                    + SINK_CAP_FLOOR_MIB + " or more, or 0 = unbounded";
        }
        return null;
    }

    /**
     * The seat count past which a recording serve prints its boot ADVISORY (never a refusal): the
     * largest N with {@code (2 + N) * ceil(WORST_STALL_MILLIS * hz / 1000) * 2 <= DECOUPLED_QUEUE_CHUNKS}
     * (the worst measured HDD stall with headroom 2, modelling two records per tick beside one per
     * seat; integer arithmetic, so a whole-tick product never rounds up a tick). 60 Hz = 213.
     * Negative when not even zero seats fit. The per-seat term is a worst-case model: the serve
     * batches every seat's takes into ONE record per tick, and a full queue costs frame time on the
     * submitting thread, never a lost record. {@code hz} must be >= 1.
     *
     */
    public static int recordModeSeatBound(int hz) {
        if (hz < 1) {
            throw new IllegalArgumentException("tick rate must be >= 1 Hz, got " + hz);
        }
        long stallTicks = ((long) WORST_STALL_MILLIS * hz + 999L) / 1000L;
        return (int) (DECOUPLED_QUEUE_CHUNKS / (stallTicks * 2L)) - 2;
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Create the file (parents included), write the CCWR header, flush. Inline-write mode. */
    public static WireRecordSink open(String path, Map<String, String> meta) throws IOException {
        return open(path, meta, false, DECOUPLED_QUEUE_CHUNKS);
    }

    /** Decoupled-capable open: {@code decoupled=true} = writer-thread mode (class doc). */
    public static WireRecordSink open(String path, Map<String, String> meta, boolean decoupled)
            throws IOException {
        return open(path, meta, decoupled, DECOUPLED_QUEUE_CHUNKS);
    }

    /**
     * Test seam: the open over a caller-owned byte stream ({@code label} stands in for the path in
     * {@link #path()} and the stdout rows), so a contract can make the disk fail on demand and pin
     * the dead-sink latch in both write modes. Every file open runs through the same body.
     *
     */
    public static WireRecordSink openOverStream(String label, OutputStream raw, Map<String, String> meta,
            boolean decoupled) throws IOException {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(meta, "meta");
        return openOver(label, raw, meta, decoupled, DECOUPLED_QUEUE_CHUNKS, 0L);
    }

    /** Test seam: queue capacity exposed so contracts can force the fail-open fallback path. */
    public static WireRecordSink open(String path, Map<String, String> meta, boolean decoupled,
            int queueCapacity) throws IOException {
        return open(path, meta, decoupled, queueCapacity, 0L);
    }

    private static WireRecordSink open(String path, Map<String, String> meta, boolean decoupled,
            int queueCapacity, long maxBytes) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(meta, "meta");
        Path p = Path.of(path);
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        return openOver(path, Files.newOutputStream(p), meta, decoupled, queueCapacity, maxBytes);
    }

    private static WireRecordSink openOver(String path, OutputStream raw, Map<String, String> meta,
            boolean decoupled, int queueCapacity, long maxBytes) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw));
        out.writeInt(MAGIC);
        out.writeShort(FORMAT_VERSION);
        out.writeLong(System.currentTimeMillis());
        out.writeShort(meta.size());
        for (Map.Entry<String, String> e : meta.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeUTF(e.getValue() == null ? "" : e.getValue());
        }
        out.flush();
        BoundedChunkDrain drain = !decoupled ? null
                : new BoundedChunkDrain("cc-ccwr-drain", queueCapacity,
                        new BoundedChunkDrain.ChunkWriter() {
                            @Override
                            public void write(byte[] chunk) throws IOException {
                                out.write(chunk);
                            }

                            @Override
                            public void flush() throws IOException {
                                out.flush();
                            }
                        });
        return new WireRecordSink(path, out, drain, maxBytes);
    }

    /**
     * Cap reached: close cleanly under the monitor, ONE stdout row, error names the cap; the
     * END trailer ({@code end.reason=capped}, no caller keys) goes in BEFORE the drain shuts down,
     * so the never-reorder drain lands it after every accepted record.
     */
    private void capNow(long tick) {
        error = "capped: bytes=" + bytesFramed + " max=" + maxBytes + " (cc.net.sinkMaxMiB)";
        System.out.println("npeer-serve wire_sink_capped path=" + path + " bytes=" + bytesFramed
                + " max=" + maxBytes + " tick=" + tick + " down=" + recordsDown + " up=" + recordsUp);
        writeEnd("capped", Map.of());
        if (drain != null) {
            drain.shutdown(2_000L);
        }
        try { out.close(); } catch (IOException ignore) { }
        closed = true;
    }

    /** Byte cap this sink was opened with (0 = unbounded); {@link #framedBytes()} counts against it. */
    public long maxBytes() { return maxBytes; }
    /** Framing + payload bytes of every record accepted so far (header excluded). */
    public long framedBytes() { return bytesFramed; }

    /** Stamp the engine frame the NEXT records belong to (-1 = pre-combat-loop traffic). */
    public void tick(long tick) {
        this.currentTick = tick;
    }

    /** Append one record + flush. Never throws (see class doc); post-close it is a no-op. */
    public synchronized void record(byte kind, byte[] payload) {
        record(kind, payload, currentTick);
    }

    /**
     * Duplex variant: append one record stamped with an EXPLICIT tick. The duplex gate's
     * two loops (SEND stamping DOWN records with the deposit tick, RECV stamping UP records with the
     * server's latest deposited tick) would race the shared {@link #tick} field — the stamp travels
     * with the call instead. Same never-throws contract.
     */
    public synchronized void record(byte kind, byte[] payload, long tick) {
        if (closed || !error.isEmpty()) {
            return;
        }
        if (drain != null) {
            // Decoupled mode: frame + enqueue only (byte-identical record layout; millis
            // stamped now = when it crossed the wire). ioNanos keeps its stall-witness
            // meaning — the submitting thread's blocking cost, which here is the enqueue
            // plus any queue-full fallback drain.
            long ioStartNanos = System.nanoTime();
            drain.submit(frame(kind, tick, payload));
            ioNanos.add(System.nanoTime() - ioStartNanos);
            String drainError = drain.error();
            if (!drainError.isEmpty()) {
                error = drainError;
                try { out.close(); } catch (IOException ignore) { }
                closed = true;
                printDeadRow(tick, drainError);
                return;
            }
            count(kind, payload.length, tick);
            if (maxBytes > 0 && bytesFramed >= maxBytes) {
                capNow(tick);
            }
            return;
        }
        try {
            // Write+flush nanos accounting for the stall witness
            // (io_ccwr=). LongAdder: recorded under this monitor from serve + reader
            // threads, read lock-free by the serve thread's witness stamps.
            long ioStartNanos = System.nanoTime();
            out.writeByte(kind);
            out.writeLong(tick);
            out.writeLong(System.currentTimeMillis());
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            ioNanos.add(System.nanoTime() - ioStartNanos);
            count(kind, payload.length, tick);
            if (maxBytes > 0 && bytesFramed >= maxBytes) {
                capNow(tick);
            }
        } catch (IOException e) {
            error = e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            try { out.close(); } catch (IOException ignore) { }
            closed = true;
            printDeadRow(tick, error);
        }
    }

    /**
     * One accepted record into the counters: DOWN and UP by their own kind, every other kind (the
     * row kinds, the END trailer included) as a row. Caller holds the monitor.
     *
     */
    private void count(byte kind, int payloadLength, long tick) {
        if (kind == KIND_UP) {
            recordsUp++;
            bytesUp += payloadLength;
        } else if (kind == KIND_DOWN) {
            recordsDown++;
            bytesDown += payloadLength;
        } else {
            recordsRows++;
            bytesRows += payloadLength;
        }
        bytesFramed += RECORD_FRAMING_BYTES + payloadLength;
        if (tick >= 0) {
            lastStampedTick = tick;
        }
    }

    /** The framed record bytes (the layout the inline path writes field by field). */
    private static byte[] frame(byte kind, long tick, byte[] payload) {
        byte[] framed = new byte[RECORD_FRAMING_BYTES + payload.length];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(framed); // big-endian default
        buf.put(kind).putLong(tick).putLong(System.currentTimeMillis())
                .putInt(payload.length).put(payload);
        return framed;
    }

    /**
     * The LOUD dead-sink row: the disk failed and every later record is a no-op, so the match from
     * here on is unrecorded — said once, the moment it happens, never only at teardown. Bounded
     * tokens only (the operator's path, an exception's one-line text, counters).
     *
     */
    private void printDeadRow(long tick, String cause) {
        System.out.println("npeer-serve wire_sink_dead path=" + path + " tick=" + tick + " error="
                + oneLine(cause, 200) + " down=" + recordsDown + " up=" + recordsUp + " rows=" + recordsRows);
    }

    private static String oneLine(String s, int max) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            b.append(c < 0x20 || c == 0x7F ? ' ' : c);
        }
        return b.toString();
    }

    /**
     * The orderly-teardown close: append the END trailer ({@code end.reason=closed}; the caller's
     * keys first, then the sink's own, the sink's winning a collision), then run {@link #close}. A
     * sink already closed is a no-op; a sink whose disk already failed writes NO trailer (none is
     * possible on a dead disk — a reader treats the missing END as truncation) and only closes. In
     * decoupled mode the END is SUBMITTED to the drain before it shuts down, so the never-reorder
     * drain lands it after every queued record; inline it is written and flushed before the stream
     * closes. A {@link #record} racing in after this returns at its closed check (same monitor).
     *
     */
    public synchronized void closeWithEnd(Map<String, String> callerKeys) {
        if (closed) {
            return;
        }
        if (error.isEmpty() && (drain == null || drain.error().isEmpty())) {
            writeEnd("closed", callerKeys == null ? Map.of() : callerKeys);
        }
        close();
    }

    /**
     * Frame + append the END trailer stamped {@link #lastStampedTick} and count it as a row; its
     * counters describe every record BEFORE it. Inline, a write failure latches the error and prints
     * the dead row (the closer then finds the sink dead). Caller holds the monitor.
     */
    private void writeEnd(String reason, Map<String, String> callerKeys) {
        LinkedHashMap<String, String> sinkKeys = new LinkedHashMap<>();
        sinkKeys.put("end.reason", reason);
        sinkKeys.put("end.last.tick", Long.toString(lastStampedTick));
        sinkKeys.put("records.down", Long.toString(recordsDown));
        sinkKeys.put("records.up", Long.toString(recordsUp));
        sinkKeys.put("records.rows", Long.toString(recordsRows));
        sinkKeys.put("bytes.framed", Long.toString(bytesFramed));
        sinkKeys.put("drain.fallbacks", Long.toString(decoupledFallbackDrains()));
        sinkKeys.put("drain.maxDepth", Long.toString(decoupledMaxQueueDepth()));
        sinkKeys.put("sink.error", error);
        byte[] payload = encodeEnd(callerKeys, sinkKeys);
        long tick = lastStampedTick;
        if (drain != null) {
            drain.submit(frame(KIND_END, tick, payload));
            if (!drain.error().isEmpty()) {
                return; // the close that follows latches the drain's error; the END is lost
            }
        } else {
            try {
                out.writeByte(KIND_END);
                out.writeLong(tick);
                out.writeLong(System.currentTimeMillis());
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                String cause = e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
                if (error.isEmpty()) {
                    error = cause;
                }
                printDeadRow(tick, cause);
                return;
            }
        }
        count(KIND_END, payload.length, tick);
    }

    /**
     * The END payload: u32 magic · u8 row version · u16 count · count × (UTF key, UTF value), the
     * caller's keys first (minus any the sink owns), then the sink's. Every string is cut to
     * {@value #END_UTF_MAX_BYTES} modified-UTF-8 bytes and the pair count to the u16 range, so the
     * encode never throws.
     */
    private static byte[] encodeEnd(Map<String, String> callerKeys, Map<String, String> sinkKeys) {
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        int callerRoom = 0xFFFF - sinkKeys.size();
        for (Map.Entry<String, String> e : callerKeys.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey();
            if (!sinkKeys.containsKey(key) && pairs.size() < callerRoom) {
                pairs.put(key, e.getValue() == null ? "" : e.getValue());
            }
        }
        pairs.putAll(sinkKeys);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(64 + 32 * pairs.size());
        DataOutputStream d = new DataOutputStream(bytes);
        try {
            d.writeInt(ROW_MAGICS[KIND_END]);
            d.writeByte(END_ROW_VERSION);
            d.writeShort(pairs.size());
            for (Map.Entry<String, String> e : pairs.entrySet()) {
                d.writeUTF(boundedUtf(e.getKey()));
                d.writeUTF(boundedUtf(e.getValue()));
            }
        } catch (IOException impossible) {
            // A byte-array stream never throws and every string is cut below writeUTF's limit.
            throw new IllegalStateException("END encode", impossible);
        }
        return bytes.toByteArray();
    }

    /** {@code s} cut to at most {@value #END_UTF_MAX_BYTES} modified-UTF-8 bytes, never mid surrogate pair. */
    static String boundedUtf(String s) {
        int bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int width = c >= 0x0001 && c <= 0x007F ? 1 : c <= 0x07FF ? 2 : 3;
            if (bytes + width > END_UTF_MAX_BYTES) {
                int cut = i > 0 && Character.isHighSurrogate(s.charAt(i - 1)) ? i - 1 : i;
                return s.substring(0, cut);
            }
            bytes += width;
        }
        return s;
    }

    /**
     * Decode an END payload into its pairs, in write order; throws {@link IOException} on a wrong
     * magic, a truncated body or trailing bytes. Public so every reel reader decodes the trailer
     * the one way {@link #verify} does.
     */
    public static LinkedHashMap<String, String> decodeEnd(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            int magic = in.readInt();
            if (magic != ROW_MAGICS[KIND_END]) {
                throw new IOException("END magic 0x" + Integer.toHexString(magic));
            }
            in.readUnsignedByte(); // row version
            int count = in.readUnsignedShort();
            LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                pairs.put(in.readUTF(), in.readUTF());
            }
            if (in.available() != 0) {
                throw new IOException("END has " + in.available() + " trailing bytes");
            }
            return pairs;
        } catch (EOFException eof) {
            throw new IOException("END truncated", eof);
        }
    }

    public String path() { return path; }
    public String error() { return error; }
    /** Cumulative record write+flush nanos, all threads (the stall-witness io_ccwr=). */
    public long ioNanosTotal() { return ioNanos.sum(); }
    public long recordsDown() { return recordsDown; }
    public long recordsUp() { return recordsUp; }
    public long bytesDown() { return bytesDown; }
    public long bytesUp() { return bytesUp; }
    /** Row-kind records accepted (the END trailer included); DOWN/UP counted apart. */
    public long recordsRows() { return recordsRows; }
    public long bytesRows() { return bytesRows; }
    /** The last accepted record stamp that was >= 0 (the END's own stamp); -1 = none. */
    public long lastStampedTick() { return lastStampedTick; }
    /** Teardown truth: 0 in inline mode. */
    public boolean decoupled() { return drain != null; }
    public long decoupledFallbackDrains() { return drain == null ? 0L : drain.fallbackDrains(); }
    public long decoupledMaxQueueDepth() { return drain == null ? 0L : drain.maxQueueDepth(); }

    /** Plain close: drain, then close the stream; writes NO trailer (see {@link #closeWithEnd}). */
    @Override
    public synchronized void close() {
        if (!closed) {
            if (drain != null) {
                drain.shutdown(2_000L);
                if (error.isEmpty() && !drain.error().isEmpty()) {
                    error = drain.error();
                }
            }
            try {
                out.close();
            } catch (IOException e) {
                if (error.isEmpty()) {
                    error = "close: " + e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
                }
            }
            closed = true;
        }
    }

    // ---------- reader / verifier (the embryo of the replay-server's file reader) ----------

    /** Decode summary of one CCWR file; failures land in {@link #error}, never thrown. */
    public static final class Verified {
        public final Map<String, String> meta = new LinkedHashMap<>();
        public int formatVersion = -1;
        public long records, down, up, gpeeFrames, awssFrames, pwvwFrames, pintFrames, pdclFrames;
        /** one-frame un-stamped PSTB admission records (one per admitted session). */
        public long pstbFrames;
        public long tickMin = Long.MAX_VALUE, tickMax = Long.MIN_VALUE;
        /**
         * PER-KIND (DOWN and UP each) non-decreasing — the duplex relaxation: the duplex gate's SEND
         * and RECV loops stamp independently, so cross-kind interleave can dip on a narrow race
         * (RECV reads tick t, SEND records t+1, RECV records t); within one kind the stamp source
         * is single-threaded and must stay monotone. File-wide monotone (every v1 recording)
         * trivially satisfies this.
         */
        public boolean ticksNonDecreasing = true;
        /** AWSS payload {@code simTick} vs record tick, loop records (tick >= 0) only. */
        public long awssTickMatches, awssTickMismatches;
        /** Mid-record EOF — tolerated (the crash-forensics case), reported, not an error. */
        public boolean truncatedTail;
        /** Row-kind records ({@value #KIND_ROW_MIN}..{@value #KIND_ROW_MAX}, the END included), each accepted by its magic. */
        public long rows;
        /** The END trailer's pairs in write order; empty without one. */
        public final Map<String, String> end = new LinkedHashMap<>();
        /** An END trailer was read; without one the record was cut short or plainly closed. */
        public boolean endSeen;
        public String error = "";
    }

    /**
     * Decode EVERY record with the existing codecs only (pre-registered criterion 3): each DOWN
     * payload must demux to 0..n GPEE frames + exactly one AWSS or PWVW frame (CRC enforced at
     * AWSS decode), OR be exactly one PSTB posture block (the once-per-admission
     * ctrl mirror — rides every sink attached before an admit); each UP payload must be exactly
     * one valid PINT frame; each row-kind payload must lead with its kind's magic (a reserved kind
     * too); the END trailer must decode and appear at most once. A header version outside
     * 1..{@value #FORMAT_VERSION} is refused before any record is read.
     */
    public static Verified verify(String path) {
        Verified v = new Verified();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(Path.of(path))))) {
            if (in.readInt() != MAGIC) {
                v.error = "bad sink magic";
                return v;
            }
            v.formatVersion = in.readShort();
            if (v.formatVersion < 1 || v.formatVersion > FORMAT_VERSION) {
                v.error = "unsupported CCWR format version " + v.formatVersion + " (reads 1.." + FORMAT_VERSION + ")";
                return v;
            }
            in.readLong(); // created epoch-millis
            int metaCount = in.readUnsignedShort();
            for (int m = 0; m < metaCount; m++) {
                v.meta.put(in.readUTF(), in.readUTF());
            }
            long prevDownTick = Long.MIN_VALUE;
            long prevUpTick = Long.MIN_VALUE;
            while (true) {
                int kind = in.read();
                if (kind < 0) {
                    return v; // clean EOF
                }
                long tick;
                byte[] payload;
                try {
                    tick = in.readLong();
                    in.readLong(); // record epoch-millis
                    int len = in.readInt();
                    if (len < 0) {
                        v.error = "record " + (v.records + 1) + ": negative payload length " + len;
                        return v;
                    }
                    payload = new byte[len];
                    in.readFully(payload);
                } catch (EOFException eof) {
                    v.truncatedTail = true;
                    return v;
                }
                v.records++;
                if (kind == KIND_UP) {
                    if (tick < prevUpTick) {
                        v.ticksNonDecreasing = false;
                    }
                    prevUpTick = tick;
                } else if (kind == KIND_DOWN) {
                    if (tick < prevDownTick) {
                        v.ticksNonDecreasing = false;
                    }
                    prevDownTick = tick;
                }
                v.tickMin = Math.min(v.tickMin, tick);
                v.tickMax = Math.max(v.tickMax, tick);
                if (kind == KIND_UP) {
                    v.up++;
                    if (!decodeUp(payload, v)) {
                        return v;
                    }
                } else if (kind == KIND_DOWN) {
                    v.down++;
                    if (!decodeDown(payload, tick, v)) {
                        return v;
                    }
                } else if (kind >= KIND_ROW_MIN && kind <= KIND_ROW_MAX) {
                    v.rows++;
                    if (!checkRow(kind, payload, v)) {
                        return v;
                    }
                } else {
                    v.error = "record " + v.records + ": unknown kind " + kind;
                    return v;
                }
            }
        } catch (IOException e) {
            v.error = e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            return v;
        }
    }

    /**
     * A row-kind record: at least magic + row version, led by its kind's magic; the END trailer is
     * decoded into {@link Verified#end}, once.
     */
    private static boolean checkRow(int kind, byte[] payload, Verified v) {
        int want = ROW_MAGICS[kind];
        int seen = payload.length < 4 ? 0 : ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        if (payload.length < 5 || want == 0 || seen != want) {
            v.error = "record " + v.records + ": row kind " + kind + " magic 0x" + Integer.toHexString(seen)
                    + " (want 0x" + Integer.toHexString(want) + ", payload " + payload.length + " B)";
            return false;
        }
        if (kind == KIND_END) {
            if (v.endSeen) {
                v.error = "record " + v.records + ": a second END trailer";
                return false;
            }
            try {
                v.end.putAll(decodeEnd(payload));
            } catch (IOException e) {
                v.error = "record " + v.records + ": END trailer: " + e.getMessage();
                return false;
            }
            v.endSeen = true;
        }
        return true;
    }

    private static boolean decodeUp(byte[] payload, Verified v) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(payload);
            // The up-lane carries two frame families (PINT intent, PDCL declared
            // discharge); each UP record holds exactly one frame, dispatched by its magic like
            // decodeDown dispatches the DOWN families.
            in.mark(4);
            int magic = new DataInputStream(in).readInt();
            in.reset();
            if (magic == FireDischargeDeclarationCodec.MAGIC) {
                cc.starsector.server.runtime.FireDischargeDeclaration decl =
                        FireDischargeDeclarationCodec.readFrame(in);
                if (decl == null || in.available() != 0) {
                    v.error = "UP record " + v.records + ": not exactly one PDCL frame";
                    return false;
                }
                v.pdclFrames++;
                return true;
            }
            // the recorded read: a reel cut by an older build holds older-schema (narrower) PINT frames
            PilotIntentFrame frame = PilotIntentWireCodec.readFrameRecorded(in);
            if (frame == null || in.available() != 0) {
                v.error = "UP record " + v.records + ": not exactly one PINT frame";
                return false;
            }
            v.pintFrames++;
            return true;
        } catch (IOException e) {
            v.error = "UP record " + v.records + ": " + e.getMessage();
            return false;
        }
    }

    private static boolean decodeDown(byte[] payload, long tick, Verified v) {
        int snapFrames = 0, viewFrames = 0, gpeeFrames = 0, pstbFrames = 0;
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(payload);
            while (in.available() > 0) {
                in.mark(4);
                int magic = new DataInputStream(in).readInt();
                in.reset();
                if (magic == GameplayEdgeEventCodec.MAGIC) {
                    GameplayEdgeEventCodec.readEvent(in);
                    v.gpeeFrames++;
                    gpeeFrames++;
                } else if (magic == AuthoritativeWorldSnapshotCodec.MAGIC) {
                    byte[] frame = AuthoritativeWorldSnapshotCodec.readFrameBytes(in);
                    List<cc.starsector.server.protocol.AuthoritativeWorldSnapshot> snaps =
                            AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(frame));
                    if (snaps.size() != 1) {
                        v.error = "DOWN record " + v.records + ": AWSS frame decoded to " + snaps.size() + " snapshots";
                        return false;
                    }
                    if (tick >= 0) {
                        if (snaps.get(0).simTick() == tick) {
                            v.awssTickMatches++;
                        } else {
                            v.awssTickMismatches++;
                        }
                    }
                    v.awssFrames++;
                    snapFrames++;
                } else if (magic == PilotWorldViewWireCodec.MAGIC) {
                    PilotWorldViewWireCodec.readFrame(in);
                    v.pwvwFrames++;
                    viewFrames++;
                } else if (magic == PostureFrameCodec.BLOCK_MAGIC) {
                    PostureFrameCodec.readBlock(in);
                    v.pstbFrames++;
                    pstbFrames++;
                } else {
                    v.error = "DOWN record " + v.records + ": unknown inner magic 0x" + Integer.toHexString(magic);
                    return false;
                }
            }
            if (pstbFrames > 0) {
                // The M4 admission mirror: the sendCtrlToSession record IS the one PSTB frame.
                if (pstbFrames != 1 || gpeeFrames + snapFrames + viewFrames != 0) {
                    v.error = "DOWN record " + v.records + ": PSTB must ride alone as the one-frame"
                            + " admission record, got " + pstbFrames + " PSTB + " + gpeeFrames
                            + " GPEE + " + snapFrames + " AWSS + " + viewFrames + " PWVW";
                    return false;
                }
                return true;
            }
            if (snapFrames + viewFrames != 1) {
                v.error = "DOWN record " + v.records + ": expected exactly one AWSS or PWVW frame, got "
                        + snapFrames + " AWSS + " + viewFrames + " PWVW";
                return false;
            }
            return true;
        } catch (IOException e) {
            v.error = "DOWN record " + v.records + ": " + e.getMessage();
            return false;
        }
    }
}
