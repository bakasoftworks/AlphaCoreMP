package cc.starsector.server.runtime;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.function.IntPredicate;

/**
 * Pull-style reader over a {@code .ccwr} container (the shape {@link WireRecordSink} writes): the
 * header once at {@link #open}, then one framed record per {@link #next} call, its payload read or
 * skipped at the caller's choice. The replay feed and the re-sim differ consume a reel from a cursor
 * they ADVANCE themselves (a bounded lookahead over the round-start-gate rows, a two-reel lock-step
 * diff); a push walk with a per-record callback cannot pause between two records, so this class
 * stands beside the push-style walker in the tools package rather than replacing it.
 *
 *
 * <p>Framing (big-endian, {@link WireRecordSink} class doc): header = u32 magic, u16 format version
 * (gated to {@code 1..}{@link WireRecordSink#FORMAT_VERSION} like every reel reader), s64 created
 * epoch-millis, u16 meta count, count × (UTF key, UTF value); record = u8 kind, s64 tick, s64
 * epoch-millis, s32 payload length, payload. No payload is ever decoded here: a kind the caller does
 * not name is handed over (or skipped) like every other record, so a newer writer's row kinds never
 * break an older consumer.
 *
 * <p>Bounds: a length above {@link #MAX_PAYLOAD_BYTES} or below zero is refused with an
 * {@link IOException} BEFORE any allocation or skip, so a corrupt or hostile length field never sizes
 * a buffer. A truncated FINAL record ends the walk like a clean EOF ({@link #next} returns null) with
 * {@link #truncated()} set — the tolerant posture every reel reader shares, because a crashed serve
 * leaves a readable reel whose last record may be cut mid-write.
 */
public final class CcwrPullCursor implements Closeable {

    /**
     * The largest payload a record may claim (16 MiB): far above any sink record (a full AWSS tick
     * image is tens of KB), far below a length that would make a reader allocate itself to death.
     */
    public static final int MAX_PAYLOAD_BYTES = 1 << 24;

    /** Parsed container header, fields in write order; {@code meta} keeps the recording order. */
    public record Header(short formatVersion, long createdEpochMillis,
            LinkedHashMap<String, String> meta) { }

    /**
     * One framed record. {@code length} is the payload length from the frame whether or not the
     * payload was read; {@code payloadOrNull} is null when the caller asked {@link #next} to skip it.
     */
    public record Record(int kind, long tick, long millis, int length, byte[] payloadOrNull) { }

    private static final int PAYLOAD_ALL = -1;
    private static final int PAYLOAD_NONE = -2;

    private final Path path;
    private final DataInputStream in;
    private final Header header;
    private long recordsRead;
    private boolean truncated;
    private boolean ended;

    private CcwrPullCursor(Path path, DataInputStream in, Header header) {
        this.path = path;
        this.in = in;
        this.header = header;
    }

    /**
     * Open a reel and parse its header; the cursor stands at the first record.
     *
     * @throws IOException on a bad magic, a version outside {@code 1..FORMAT_VERSION}, or a short header
     */
    public static CcwrPullCursor open(Path path) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
        try {
            return new CcwrPullCursor(path, in, readHeader(in, path));
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /**
     * The header gate, the same range rule the push walker and the sink's own verifier apply: a
     * reader pinning ONE version would refuse every reel already on disk the moment the writer bumps,
     * and one ignoring the version would misread a future layout. Every version in range shares this
     * header and the record framing; a newer version only adds record kinds.
     */
    static Header readHeader(DataInputStream in, Path path) throws IOException {
        try {
            if (in.readInt() != WireRecordSink.MAGIC) {
                throw new IOException("bad CCWR magic in " + path);
            }
            short version = in.readShort();
            if (version < 1 || version > WireRecordSink.FORMAT_VERSION) {
                throw new IOException("unsupported CCWR format version " + version + " in " + path
                        + " (reads 1.." + WireRecordSink.FORMAT_VERSION + ")");
            }
            long created = in.readLong();
            int metaCount = in.readUnsignedShort();
            LinkedHashMap<String, String> meta = new LinkedHashMap<>();
            for (int m = 0; m < metaCount; m++) {
                String key = in.readUTF();
                meta.put(key, in.readUTF());
            }
            return new Header(version, created, meta);
        } catch (EOFException eof) {
            throw new IOException("truncated CCWR header in " + path, eof);
        }
    }

    public Header header() {
        return header;
    }

    public Path path() {
        return path;
    }

    /** Records returned so far (a refused or truncated record is not counted). */
    public long recordsRead() {
        return recordsRead;
    }

    /** True once {@link #next} returned null because the final record was cut short, not at a clean EOF. */
    public boolean truncated() {
        return truncated;
    }

    /**
     * The next record, or null at the end of the reel (clean EOF, or a truncated final record —
     * {@link #truncated()} tells which; every later call returns null too). {@code withPayload=false}
     * skips the payload bytes without allocating; the frame's length still rides the record.
     *
     * @throws IOException a length above {@link #MAX_PAYLOAD_BYTES} or below zero (refused before any
     *         read of the payload), or an I/O failure other than a short final record
     */
    public Record next(boolean withPayload) throws IOException {
        return advance(withPayload ? PAYLOAD_ALL : PAYLOAD_NONE);
    }

    /**
     * {@link #next(boolean)} with the payload decision taken PER KIND, after the kind byte is read:
     * {@code payloadFor.test(kind)} true = read it, false = skip it without allocating. The reel
     * readers that want only the row kinds (the replay feed) or everything but the UP lane (the
     * differ) walk a multi-hundred-MB reel without allocating the payloads they drop.
     */
    public Record next(IntPredicate payloadFor) throws IOException {
        return advance(payloadFor);
    }

    /**
     * Payload-skipping forward walk to the END trailer: the trailer's pairs (decoded the one way the
     * sink's verifier does), or null when the reel holds no END (a crash, a dead disk, a plain close,
     * or an END cut short). {@code kindCountsOrNull}, when given, receives one increment per record
     * at its kind byte (an array of at least 256 ints; a shorter array simply misses the higher
     * kinds) — the per-kind census a boot refusal reads before any payload is decoded.
     *
     * @throws IOException a refused length, a malformed END body, or an I/O failure
     */
    public static LinkedHashMap<String, String> scanEndOrNull(Path path, int[] kindCountsOrNull)
            throws IOException {
        LinkedHashMap<String, String> end = null;
        try (CcwrPullCursor cursor = open(path)) {
            while (true) {
                Record r = cursor.advance(k -> k == WireRecordSink.KIND_END);
                if (r == null) {
                    return end;
                }
                if (kindCountsOrNull != null && r.kind() < kindCountsOrNull.length) {
                    kindCountsOrNull[r.kind()]++;
                }
                if (r.kind() == WireRecordSink.KIND_END) {
                    end = WireRecordSink.decodeEnd(r.payloadOrNull());
                }
            }
        }
    }

    /** Read one frame; the payload is read for {@code PAYLOAD_ALL} or the named kind, skipped otherwise. */
    private Record advance(int payloadKind) throws IOException {
        return advance(payloadKind == PAYLOAD_ALL ? k -> true : payloadKind == PAYLOAD_NONE ? k -> false
                : k -> k == payloadKind);
    }

    /** Read one frame; the payload is read when {@code payloadFor} accepts its kind, skipped otherwise. */
    private Record advance(IntPredicate payloadFor) throws IOException {
        if (ended) {
            return null;
        }
        int kind = in.read();
        if (kind < 0) {
            ended = true;
            return null;
        }
        long tick;
        long millis;
        int length;
        try {
            tick = in.readLong();
            millis = in.readLong();
            length = in.readInt();
        } catch (EOFException eof) {
            return endTruncated();
        }
        long ordinal = recordsRead + 1;
        if (length < 0) {
            throw new IOException("record " + ordinal + ": negative payload length " + length + " in " + path);
        }
        if (length > MAX_PAYLOAD_BYTES) {
            throw new IOException("record " + ordinal + ": payload length " + length
                    + " exceeds the reader ceiling " + MAX_PAYLOAD_BYTES + " in " + path);
        }
        byte[] payload = null;
        try {
            if (payloadFor.test(kind)) {
                payload = new byte[length];
                in.readFully(payload);
            } else {
                in.skipNBytes(length);
            }
        } catch (EOFException eof) {
            return endTruncated();
        }
        recordsRead++;
        return new Record(kind, tick, millis, length, payload);
    }

    private Record endTruncated() {
        ended = true;
        truncated = true;
        return null;
    }

    @Override
    public void close() throws IOException {
        ended = true;
        in.close();
    }
}
