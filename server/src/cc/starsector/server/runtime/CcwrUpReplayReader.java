package cc.starsector.server.runtime;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CCWR v1 <b>UP-lane replay reader</b> (queue row 3.2, ghost second client).
 *
 * <p>Walks one {@link WireRecordSink}-written CCWR file and returns, in file order, every UP
 * record's gate-stamped server tick + its decoded {@link PilotIntentFrame} — the recorded human's
 * per-tick predict-lane intent stream, exactly the input shape the server's predict-replay path
 * consumes. DOWN records are skipped without decoding (the replay consumes inputs, not snapshots;
 * the future replay <em>server</em> is the consumer that re-speaks DOWN payloads). The record walk
 * is modeled on {@link WireRecordSink#verify}: header (magic · version · created · meta) then
 * {@code kind · tick · millis · length · payload}, a truncated final record tolerated (the
 * crash-forensics case) and reported, never thrown. Container versions {@code 1..FORMAT_VERSION}
 * are read; a kind other than DOWN / UP is skipped and counted in {@link Replay#unknownKinds}.
 */
public final class CcwrUpReplayReader {

    /** One recorded UP frame: the gate-stamped server tick + the decoded pilot intent. */
    public record TickFrame(long tick, PilotIntentFrame frame) { }

    /** Decode result; failures land in {@link #error} (frames read so far are kept), never thrown. */
    public static final class Replay {
        public final Map<String, String> meta = new LinkedHashMap<>();
        public final List<TickFrame> frames = new ArrayList<>();
        public long recordsDown;
        /** PDCL declared-discharge UP records seen (decoded fail-closed, counted, not replayed here). */
        public long recordsDecl;
        /** Records of any kind other than DOWN / UP (later-version row kinds): skipped undecoded, counted. */
        public long unknownKinds;
        public boolean truncatedTail;
        public String error = "";
    }

    private CcwrUpReplayReader() {
    }

    /**
     * Reads one reel; when it carried record kinds other than
     * DOWN / UP (a later container version's row kinds, opaque to an intent replay) prints ONE
     * stdout line naming their count — once per reel, never per record.
     */
    public static Replay read(String path) {
        Replay r = readRecords(path, new Replay());
        if (r.unknownKinds > 0) {
            System.out.println("ccwr-up-replay: skipped unknown_kinds=" + r.unknownKinds
                    + " (record kinds other than DOWN/UP carry no intent)");
        }
        return r;
    }

    private static Replay readRecords(String path, Replay r) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(Path.of(path))))) {
            if (in.readInt() != WireRecordSink.MAGIC) {
                r.error = "bad sink magic";
                return r;
            }
            int formatVersion = in.readShort();
            // Tolerant range: every version shares this header + framing; a later one only adds kinds.
            if (formatVersion < 1 || formatVersion > WireRecordSink.FORMAT_VERSION) {
                r.error = "unsupported CCWR format version " + formatVersion
                        + " (reads 1.." + WireRecordSink.FORMAT_VERSION + ")";
                return r;
            }
            in.readLong(); // created epoch-millis
            int metaCount = in.readUnsignedShort();
            for (int m = 0; m < metaCount; m++) {
                r.meta.put(in.readUTF(), in.readUTF());
            }
            long records = 0;
            while (true) {
                int kind = in.read();
                if (kind < 0) {
                    return r; // clean EOF
                }
                long tick;
                byte[] payload;
                try {
                    tick = in.readLong();
                    in.readLong(); // record epoch-millis
                    int len = in.readInt();
                    if (len < 0) {
                        r.error = "record " + (records + 1) + ": negative payload length " + len;
                        return r;
                    }
                    payload = new byte[len];
                    in.readFully(payload);
                } catch (EOFException eof) {
                    r.truncatedTail = true;
                    return r;
                }
                records++;
                if (kind == WireRecordSink.KIND_UP) {
                    ByteArrayInputStream up = new ByteArrayInputStream(payload);
                    // UP records also carry PDCL declared-discharge frames (recorded in
                    // wire order alongside the PINT intents). Replay of declarations is a
                    // declared-fire-lane concern, not this intent replayer's: decode fail-closed
                    // (a corrupt record still errors) but only COUNT them here.
                    if (payload.length >= Integer.BYTES
                            && (((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF))
                                == FireDischargeDeclarationCodec.MAGIC) {
                        if (FireDischargeDeclarationCodec.readFrame(up) == null || up.available() != 0) {
                            r.error = "UP record " + records + ": not exactly one PDCL frame";
                            return r;
                        }
                        r.recordsDecl++;
                        continue;
                    }
                    // Recorded-corpus compat read (v5|v6): a decoded v5 frame
                    // carries TUBE_ORDINAL_ABSENT, so a ghost replay re-encodes it as a v6
                    // ABSENT frame — behavior-identical (the server tube mirror skips on -1).
                    PilotIntentFrame frame = PilotIntentWireCodec.readFrameRecorded(up);
                    if (frame == null || up.available() != 0) {
                        r.error = "UP record " + records + ": not exactly one PINT frame";
                        return r;
                    }
                    r.frames.add(new TickFrame(tick, frame));
                } else if (kind == WireRecordSink.KIND_DOWN) {
                    r.recordsDown++;
                } else {
                    r.unknownKinds++; // skipped undecoded and counted; read() reports the total once
                }
            }
        } catch (IOException e) {
            r.error = e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            return r;
        }
    }
}
