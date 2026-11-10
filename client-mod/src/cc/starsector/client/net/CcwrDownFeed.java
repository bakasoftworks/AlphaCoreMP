package cc.starsector.client.net;

import cc.starsector.client.ServerTickRate;
import cc.starsector.server.runtime.WireRecordSink;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * wq P18 — the DOWN twin of {@link CcwrIntentSourceReader}: a STREAMING walk over one
 * {@code WireRecordSink}-written CCWR recording that yields every {@code KIND_DOWN} record in file
 * order (tick, epoch-millis, payload) and splits a record body into its inner wire frames. Feeds
 * {@link ReelLoopbackDoor}, which re-speaks those frames into the REAL observer session.
 *
 * <p>Streaming, not loaded: a serve reel is hundreds of MB (the 09-05 fixture = 270 MB, 76 k
 * records), so the walk holds ONE record at a time on the door's pacer thread; UP records are
 * skipped without decoding. Same classloader-B allowlist as the intent reader (class doc there):
 * {@code URL.openStream} for the bytes, {@link WireBytes} primitives, {@link WireRecordSink} for
 * compile-time constants ONLY (inlined — no runtime reference, bytecode-guarded by the verifier).
 *
 * <p><b>Split law.</b> A stamped tick record body is {@code [GPEE*][EDSP*][AWSS chunk*]} and an
 * un-stamped fan record is ONE ctrl frame ({@code DownTickComposition} / the door's fan sites);
 * frames are 4-char-magic-led and self-delimited only by their own codec read, so the split runs
 * the client's own demux ({@link MultiplexedServerStreamReader#readMessage}) over a
 * position-exposing byte stream and cuts at the consumed extents — byte-exact, and an unknown
 * magic throws exactly where the live client would. {@code SNAPSHOT}/{@code AWSD_SNAPSHOT} ride
 * the SNAP channel live, everything else CTRL ({@code sendTickToSession}).
 */
public final class CcwrDownFeed implements AutoCloseable {

    /** One {@code KIND_DOWN} record: the gate-stamped tick (-1 = un-stamped fan record), the
     * record epoch-millis (the pacing timeline) and the raw body. */
    public record DownRecord(long tick, long millis, byte[] payload) { }

    /** One inner wire frame cut from a record body + its decoded message (the SNAP/CTRL lane
     * decision and the negative-control filter read it). */
    public record Inner(boolean snap, byte[] bytes, ServerStreamMessage message) { }

    /** Header meta (key -> value) as written by {@code MatchRecordMeta}. */
    public final Map<String, String> meta = new LinkedHashMap<>();
    public final long createdMillis;
    public final String path;
    public long recordsDown;
    public long recordsUp;
    /** Row-kind records ({@code KIND_ROW_MIN..KIND_ROW_MAX}, the END trailer included) skipped undecoded. */
    public long rowsSkipped;
    public boolean truncatedTail;
    /**
     * The reader's payload ceiling, a NEW bound: every CCWR reader
     * allocates {@code new byte[len]} straight from the s32 length, so a corrupt or crafted length
     * up to 2 GiB raised an OutOfMemoryError that escaped the callers' IOException catches. 16 MiB
     * sits far above any record the sink writes (a DOWN batch, a 255-seat TAKES row, a custom spool
     * VSUB) and is a typed refusal, never an allocation. Shared by {@link CcwrIntentSourceReader}.
     */
    public static final int MAX_PAYLOAD_BYTES = 1 << 24;
    private final InputStream in;
    private long records;
    private boolean ended;

    private CcwrDownFeed(String path, InputStream in, long createdMillis) {
        this.path = path;
        this.in = in;
        this.createdMillis = createdMillis;
    }

    /** Open {@code absPath} and parse the header (magic · version · created · meta); the record
     * cursor sits at the first record. Throws on a bad magic / version (a synthetic feed fails
     * loud, never replays garbage). */
    public static CcwrDownFeed open(String absPath) throws IOException {
        InputStream in = new BufferedInputStream(openFileStream(absPath), 1 << 16);
        try {
            if (WireBytes.readInt(in) != WireRecordSink.MAGIC) {
                throw new IOException("bad CCWR sink magic: " + absPath);
            }
            int formatVersion = WireBytes.readUnsignedShort(in);
            // Tolerant range: every version shares this header + framing; a later one only adds row kinds —
            // the same 1..N law as the server-side readers (CcwrUpReplayReader, ReplayPlaybackServer).
            if (formatVersion < 1 || formatVersion > WireRecordSink.FORMAT_VERSION) {
                throw new IOException("unsupported CCWR format version " + formatVersion
                        + " (reads 1.." + WireRecordSink.FORMAT_VERSION + ")");
            }
            long created = WireBytes.readLong(in);
            CcwrDownFeed feed = new CcwrDownFeed(absPath, in, created);
            int metaCount = WireBytes.readUnsignedShort(in);
            for (int m = 0; m < metaCount; m++) {
                String key = readUtf(in);
                String value = readUtf(in);
                feed.meta.put(key, value);
            }
            return feed;
        } catch (IOException | RuntimeException e) {
            try {
                in.close();
            } catch (IOException ignore) {
                // the open failure is the error
            }
            throw e;
        }
    }

    /**
     * The reel's {@code knob.tickRate} meta, or {@code dflt} when absent / unparseable or outside
     * [{@link ServerTickRate#MIN_TICKS_PER_SECOND}, {@link ServerTickRate#MAX_TICKS_PER_SECOND}] —
     * the same range the client accepts from a live WELCOME, and one that fits the door's u16 field.
     * The reel file is untrusted input, so a value the live
     * client would refuse falls back to the default instead of reaching the door's handshake rows.
     */
    public int tickRateOr(int dflt) {
        String raw = meta.get("knob.tickRate");
        if (raw == null) {
            return dflt;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= ServerTickRate.MIN_TICKS_PER_SECOND && v <= ServerTickRate.MAX_TICKS_PER_SECOND ? v : dflt;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /**
     * The next {@code KIND_DOWN} record in file order, or {@code null} at the end (a clean EOF at
     * a record boundary, or the oracle's tolerated truncated-final-record case — then
     * {@link #truncatedTail} is set). UP records and row-kind records (format 2's
     * {@code KIND_ROW_MIN..KIND_ROW_MAX}, the END trailer included) are skipped undecoded and
     * counted — the server-side readers' same law. A payload length above
     * {@link #MAX_PAYLOAD_BYTES} throws before any allocation. Any other malformation throws.
     */
    public DownRecord next() throws IOException {
        while (!ended) {
            int kind = WireBytes.readOrEof(in);
            if (kind < 0) {
                ended = true;
                return null;
            }
            long tick;
            long millis;
            byte[] payload;
            try {
                tick = WireBytes.readLong(in);
                millis = WireBytes.readLong(in);
                int len = WireBytes.readInt(in);
                if (len < 0) {
                    throw new IOException("record " + (records + 1) + ": negative payload length " + len);
                }
                if (len > MAX_PAYLOAD_BYTES) {
                    throw new IOException("record " + (records + 1) + ": payload length " + len
                            + " exceeds the reader ceiling " + MAX_PAYLOAD_BYTES);
                }
                payload = new byte[len];
                WireBytes.readFully(in, payload);
            } catch (IOException eof) {
                if (!"unexpected end of stream".equals(eof.getMessage())) {
                    throw eof;
                }
                truncatedTail = true;
                ended = true;
                return null;
            }
            records++;
            if (kind == WireRecordSink.KIND_DOWN) {
                recordsDown++;
                return new DownRecord(tick, millis, payload);
            } else if (kind == WireRecordSink.KIND_UP) {
                recordsUp++;
            } else if (kind >= WireRecordSink.KIND_ROW_MIN && kind <= WireRecordSink.KIND_ROW_MAX) {
                rowsSkipped++; // the payload is already consumed above: exactly `len` bytes skipped
            } else {
                throw new IOException("record " + records + ": unknown kind " + kind);
            }
        }
        return null;
    }

    /** Records walked so far (both kinds). */
    public long recordsWalked() {
        return records;
    }

    /** The oldest GPEE schema {@link #restampLegacyEdgeFrame} lifts to the live one: 16 → 17 is
     * add-a-kind (BLAST_HIT), width and every older kind's slots unchanged. 15 stays out — v16
     * re-meant the long slot of WEAPON_DISCHARGE. */
    static final int EDGE_RESTAMP_FLOOR = 16;

    /** The live GPEE schema the floor above was decided against. A schema bump leaves this behind,
     * which turns the restamp OFF (old reels throw loudly, as before) until the bump's author
     * re-decides the floor: same width + add-a-kind keeps it, a re-meant slot raises it. */
    static final int EDGE_RESTAMP_DECIDED_AT = 17;

    /**
     * REEL COMPAT: a reel recorded by an older serve
     * carries GPEE frames stamped with that serve's schema, and the live reader
     * ({@link ClientGameplayEdgeEventReader}) is exact-match by law, so without this every reel
     * older than the last GPEE bump died at its first event tick. The compat lives HERE, in the
     * recording feed, never in the live wire reader: a frame at {@code at} whose schema is in
     * {@code [EDGE_RESTAMP_FLOOR, live)} is re-stamped IN PLACE to the live schema, so both this
     * split's demux and the observer session's own decode read it. A kind born after the frame's
     * stamp (BLAST_HIT in a schema-16 frame) is left alone and refuses at the reader, the server
     * codec's own law ({@code GameplayEdgeEventCodec}: kind requires schema ≥ its min).
     *
     * @return whether the two schema bytes were rewritten
     */
    static boolean restampLegacyEdgeFrame(byte[] body, int at) {
        final int live = ClientGameplayEdgeEventReader.SCHEMA_VERSION;
        if (live != EDGE_RESTAMP_DECIDED_AT || body.length - at < ClientGameplayEdgeEventReader.FRAME_BYTES) {
            return false;
        }
        if (intAt(body, at) != ClientGameplayEdgeEventReader.MAGIC) {
            return false;
        }
        int schema = ((body[at + 8] & 0xff) << 8) | (body[at + 9] & 0xff); // magic(4) + typeCode(4)
        if (schema < EDGE_RESTAMP_FLOOR || schema >= live) {
            return false;
        }
        int kindCode = intAt(body, at + 26); // + schema(2) + simTick(8) + entityId(8)
        if (kindCode == ClientGameplayEdgeEvent.Kind.BLAST_HIT.wireCode()) {
            return false; // born at 17: never lawful in an older frame
        }
        body[at + 8] = (byte) (live >>> 8);
        body[at + 9] = (byte) live;
        return true;
    }

    private static int intAt(byte[] b, int at) {
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    /**
     * Cut one record body into its inner frames (class doc). Throws on an unknown magic or a
     * frame that runs past the body — the same fail-closed posture as the live demux. Legacy GPEE
     * frames are re-stamped in {@code body} first ({@link #restampLegacyEdgeFrame}).
     */
    public static List<Inner> split(byte[] body) throws IOException {
        List<Inner> out = new ArrayList<>(4);
        PositionStream in = new PositionStream(body);
        while (in.position() < body.length) {
            int start = in.position();
            restampLegacyEdgeFrame(body, start);
            ServerStreamMessage m = MultiplexedServerStreamReader.readMessage(in);
            if (m == null) {
                break; // cannot happen with bytes left, kept for the null contract
            }
            int end = in.position();
            byte[] frame = new byte[end - start];
            System.arraycopy(body, start, frame, 0, frame.length);
            boolean snap = m.kind() == ServerStreamMessage.Kind.SNAPSHOT
                    || m.kind() == ServerStreamMessage.Kind.AWSD_SNAPSHOT;
            out.add(new Inner(snap, frame, m));
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        ended = true;
        in.close();
    }

    /** A {@link ByteArrayInputStream} that exposes its cursor — the split's extent meter. The
     * client readers use single-byte primitives + {@code readFully} only (no read-ahead), so the
     * cursor after a read is exactly the frame's end. */
    private static final class PositionStream extends ByteArrayInputStream {
        PositionStream(byte[] buf) {
            super(buf);
        }

        int position() {
            return pos;
        }
    }

    /** Open the recording as a binary stream using only classloader-B-allowlisted names. */
    private static InputStream openFileStream(String absPath) throws IOException {
        try {
            return new URL("file:///" + absPath.replace('\\', '/')).openStream();
        } catch (MalformedURLException e) {
            throw new IOException("cannot open CCWR recording as a file URL: " + absPath, e);
        }
    }

    /** One {@code DataOutputStream.writeUTF} value (u16 length + modified-UTF-8 bytes); meta
     * values are ASCII in practice, so a plain UTF-8 decode is exact for them. */
    private static String readUtf(InputStream in) throws IOException {
        byte[] bytes = new byte[WireBytes.readUnsignedShort(in)];
        WireBytes.readFully(in, bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
