package cc.starsector.client.net;

import cc.starsector.server.runtime.PilotIntentFrame;
import cc.starsector.server.runtime.PilotIntentWireCodec;
import cc.starsector.server.runtime.WireRecordSink;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The IN-GAME, allowlist-clean sibling of
 * the server's {@code CcwrUpReplayReader} (the oracle): walks one {@code WireRecordSink}-written
 * CCWR recording and returns every UP record's decoded {@link PilotIntentFrame} in file order —
 * the recorded human's per-tick predict-lane input stream the {@code cc.predict.intentSource}
 * override feeds into the otherwise-REAL predict client. DOWN records are skipped without decoding.
 *
 * <p><b>Why a sibling and not the oracle itself.</b> The mod is loaded by Starsector's script
 * classloader {@code B} — a STATIC {@code java.io}/{@code java.nio.file} name blocklist
 * that denies the oracle's {@code DataInputStream}, {@code EOFException} and
 * {@code java.nio.file.Files}. This walk therefore uses only allowed names: {@link WireBytes}
 * over {@link BufferedInputStream}/{@link ByteArrayInputStream}, and the already-folded,
 * already-clean {@link PilotIntentWireCodec#readFrameRecorded(InputStream)} for the PINT payloads.
 * Byte-for-byte parity with the oracle is asserted game-free.
 *
 *
 * <p><b>The file stream.</b> {@code java.io.FileInputStream} is NOT on {@code B}'s allowlist and
 * the one allowed file class ({@code FileReader}) decodes chars, so it cannot carry binary CCWR
 * bytes. {@code java.net.*} is unrestricted, so the recording is opened via
 * {@code new URL("file:///...").openStream()}: the names the mod's bytecode makes {@code B} load —
 * {@code URL}, {@code InputStream}, {@code IOException} — are all allowed, and the concrete
 * stream object the JDK returns is constructed by JDK-internal code whose classes never pass
 * through {@code B} (the same measured loader model that lets the bare socket streams work
 * in-game: that model's {@code Socket$SocketInputStream} ALLOW row).
 *
 * <p>{@link WireRecordSink} is referenced for its format constants ONLY — all compile-time
 * constants, inlined by javac, so the compiled class carries no runtime reference to that
 * non-folded server class (mechanically asserted by the verifier wrapper's bytecode check).
 *
 * <p>Unlike the oracle (which latches errors into a result field for crash forensics), this reader
 * THROWS on a malformed file — a synthetic seat must fail loud, never replay garbage. The oracle's
 * one tolerance is kept: a mid-record end of stream is the truncated-final-record case (a recording
 * stopped mid-write), reported via {@link Recording#truncatedTail} with the frames read so far kept.
 */
public final class CcwrIntentSourceReader {

    /** One parsed {@code cc.predict.intentSource} value: the recording path + the start offset. */
    public record Spec(String ccwrPath, int skipFrames) { }

    /** One decoded recording: the UP frames in file order + the walk's observability counters. */
    public static final class Recording {
        public final List<PilotIntentFrame> frames = new ArrayList<>();
        public long recordsDown;
        /** Row-kind records ({@code KIND_ROW_MIN..KIND_ROW_MAX}, the END trailer included) skipped undecoded. */
        public long rowsSkipped;
        public boolean truncatedTail;
    }

    private CcwrIntentSourceReader() {
    }

    /**
     * Parse a {@code cc.predict.intentSource} value: {@code ccwr:<abs path>[:skip=N]}. The path may
     * itself contain {@code ':'} (a Windows drive letter), so the optional skip suffix is matched
     * from the END, never by splitting on {@code ':'}. Malformed values throw
     * {@link IllegalArgumentException} (knob discipline: a cert-rig knob fails loud at load).
     */
    public static Spec parseSpec(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.startsWith("ccwr:")) {
            throw new IllegalArgumentException(
                    "cc.predict.intentSource must be ccwr:<abs path>[:skip=N]; got: " + value);
        }
        String rest = value.substring("ccwr:".length());
        int skip = 0;
        int at = rest.lastIndexOf(":skip=");
        if (at >= 0) {
            String n = rest.substring(at + ":skip=".length());
            try {
                skip = Integer.parseInt(n);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "cc.predict.intentSource skip is not an integer: " + n);
            }
            if (skip < 0) {
                throw new IllegalArgumentException(
                        "cc.predict.intentSource skip must be >= 0; got " + skip);
            }
            rest = rest.substring(0, at);
        }
        if (rest.isEmpty()) {
            throw new IllegalArgumentException(
                    "cc.predict.intentSource has an empty recording path: " + value);
        }
        return new Spec(rest, skip);
    }

    /**
     * Read {@code absPath} (a CCWR recording) and return its UP frames in file order. Header and
     * record framing exactly mirror the oracle's walk: header (magic · version · created · meta)
     * then {@code kind · tick · millis · length · payload}; the gate-stamped record tick is NOT
     * kept (the live session echoes its OWN snapshot tick as frameSeq — D-2: only the input VALUES
     * come from the recording).
     */
    public static Recording read(String absPath) throws IOException {
        Recording r = new Recording();
        try (InputStream in = new BufferedInputStream(openFileStream(absPath))) {
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
            WireBytes.readLong(in); // created epoch-millis
            int metaCount = WireBytes.readUnsignedShort(in);
            for (int m = 0; m < metaCount; m++) {
                skipUtf(in); // key
                skipUtf(in); // value
            }
            long records = 0;
            while (true) {
                int kind = WireBytes.readOrEof(in);
                if (kind < 0) {
                    return r; // clean EOF at a record boundary
                }
                byte[] payload;
                try {
                    WireBytes.readLong(in); // gate-stamped server tick (unused — see method doc)
                    WireBytes.readLong(in); // record epoch-millis
                    int len = WireBytes.readInt(in);
                    if (len < 0) {
                        throw new IOException(
                                "record " + (records + 1) + ": negative payload length " + len);
                    }
                    if (len > CcwrDownFeed.MAX_PAYLOAD_BYTES) {
                        throw new IOException("record " + (records + 1) + ": payload length " + len
                                + " exceeds the reader ceiling " + CcwrDownFeed.MAX_PAYLOAD_BYTES);
                    }
                    payload = new byte[len];
                    WireBytes.readFully(in, payload);
                } catch (IOException eof) {
                    // WireBytes premature-EOF throws carry exactly this message (EOFException is
                    // not on B's allowlist, so the type cannot discriminate); anything else is a
                    // real walk error and rethrows.
                    if (!"unexpected end of stream".equals(eof.getMessage())) {
                        throw eof;
                    }
                    r.truncatedTail = true; // the oracle's tolerated truncated-final-record case
                    return r;
                }
                records++;
                if (kind == WireRecordSink.KIND_UP) {
                    ByteArrayInputStream up = new ByteArrayInputStream(payload);
                    // Recorded-corpus compat read (v5|v6) — parity with the oracle's
                    // identical switch; the strict live-lane readFrame refuses prior-width records.
                    PilotIntentFrame frame = PilotIntentWireCodec.readFrameRecorded(up);
                    if (frame == null || up.available() != 0) {
                        throw new IOException("UP record " + records + ": not exactly one PINT frame");
                    }
                    r.frames.add(frame);
                } else if (kind == WireRecordSink.KIND_DOWN) {
                    r.recordsDown++;
                } else if (kind >= WireRecordSink.KIND_ROW_MIN && kind <= WireRecordSink.KIND_ROW_MAX) {
                    // Format 2's row kinds (the END trailer included): skipped undecoded and
                    // counted, the server-side readers' same law; the payload is already consumed.
                    r.rowsSkipped++;
                } else {
                    throw new IOException("record " + records + ": unknown kind " + kind);
                }
            }
        }
    }

    /** Open the recording as a binary stream using only classloader-B-allowed names (class doc). */
    private static InputStream openFileStream(String absPath) throws IOException {
        try {
            return new URL("file:///" + absPath.replace('\\', '/')).openStream();
        } catch (MalformedURLException e) {
            throw new IOException("cannot open CCWR recording as a file URL: " + absPath, e);
        }
    }

    /** Skip one {@code DataOutputStream.writeUTF} value (u16 length + modified-UTF-8 bytes). */
    private static void skipUtf(InputStream in) throws IOException {
        byte[] scratch = new byte[WireBytes.readUnsignedShort(in)];
        WireBytes.readFully(in, scratch);
    }
}
