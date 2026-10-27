package cc.starsector.server.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>REPLAY PLAYBACK SERVER</b>: a tiny
 * separate process that re-speaks a {@link WireRecordSink} CCWR recording's DOWN payloads over a
 * real socket — port-file handshake and all — so the real predict client connects and <em>cannot
 * tell</em>. A replay is a faithful RE-PRESENTATION, not a re-simulation: every DOWN record's
 * exact multiplexed bytes (0..n {@code GPEE} + 1 {@code AWSS}/{@code PWVW}) are written verbatim,
 * paced to the recording's own epoch-millis timeline (the re-emission pacer, {@code --speed}
 * scaled; {@code 0} = unpaced), and one PINT frame is read back per recorded DOWN→UP pair so the
 * strict ping-pong the live {@link PilotIntentSocketServer} loop established still holds. Schema
 * folds detect-not-migrate: a file {@link WireRecordSink#verify} cannot fully decode is REFUSED
 * before the socket ever binds. No live-lane class is touched.
 *
 * <p>Three modes (one class so the file reader/digest stay single-sourced):
 * <ul>
 *   <li>{@code serve} — the playback server: verify + load the recording, bind an ephemeral
 *       socket (loopback unless {@code --off-loopback}), write the {@link PilotPortFile}
 *       handshake file, serve one client the whole recording, write a properties receipt.
 *       {@code --record} re-records the replay session itself (DOWN verbatim + the fresh UP
 *       responses) to a new CCWR via the unchanged {@link WireRecordSink}.</li>
 *   <li>{@code verify-client} — the game-free acceptance consumer: resolves the endpoint ONLY
 *       from the port file (the real client's resolution path), reads with the live client's
 *       demux shape (GPEE routed, AWSS decoded, PWVW honored), answers each exchange with a
 *       neutral PINT frame, and digests every received socket byte so byte-verbatim
 *       re-presentation is provable end to end.</li>
 *   <li>{@code digest} — file-side expected values: verifies + walks the recording and prints
 *       DOWN/UP counts, byte totals, the concatenated-DOWN SHA-256, and the recorded span.</li>
 * </ul>
 */
public final class ReplayPlaybackServer {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 60_000;
    private static final int PORT_FILE_POLL_MILLIS = 250;

    private ReplayPlaybackServer() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: ReplayPlaybackServer <serve|verify-client|digest> [options]");
            System.exit(1);
        }
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        switch (args[0]) {
            case "serve" -> System.exit(serve(rest));
            case "verify-client" -> System.exit(verifyClient(rest));
            case "digest" -> System.exit(digest(rest));
            default -> {
                System.err.println("unknown mode: " + args[0]);
                System.exit(1);
            }
        }
    }

    // ---------- the shared CCWR record walk (modeled on WireRecordSink.verify) ----------

    private record Rec(byte kind, long tick, long millis, byte[] payload) {
    }

    private static final class Recording {
        final Map<String, String> meta = new LinkedHashMap<>();
        final List<Rec> records = new ArrayList<>();
        long downRecords, upRecords, downBytes;
        /** Records of any kind other than DOWN / UP (later-version row kinds): counted, not replayed. */
        long unknownKinds;
        long firstDownMillis = -1, lastDownMillis = -1;
        boolean truncatedTail;
        String downSha256 = "";
    }

    /** Load every complete record (truncated tail tolerated, reported) + the DOWN digest/span. */
    private static Recording load(String path) throws IOException {
        Recording r = new Recording();
        MessageDigest downDigest = newSha256();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(Path.of(path))))) {
            if (in.readInt() != WireRecordSink.MAGIC) {
                throw new IOException("bad sink magic: " + path);
            }
            int formatVersion = in.readShort();
            // Tolerant range: every version shares this header + framing; a later one only adds kinds.
            if (formatVersion < 1 || formatVersion > WireRecordSink.FORMAT_VERSION) {
                throw new IOException("unsupported CCWR format version " + formatVersion
                        + " (reads 1.." + WireRecordSink.FORMAT_VERSION + ")");
            }
            in.readLong(); // created epoch-millis
            int metaCount = in.readUnsignedShort();
            for (int m = 0; m < metaCount; m++) {
                r.meta.put(in.readUTF(), in.readUTF());
            }
            while (true) {
                int kind = in.read();
                if (kind < 0) {
                    break; // clean EOF
                }
                long tick;
                long millis;
                byte[] payload;
                try {
                    tick = in.readLong();
                    millis = in.readLong();
                    int len = in.readInt();
                    if (len < 0) {
                        throw new IOException("negative payload length " + len);
                    }
                    payload = new byte[len];
                    in.readFully(payload);
                } catch (EOFException eof) {
                    r.truncatedTail = true;
                    break;
                }
                // A kind other than DOWN / UP (a later version's
                // row kinds) is counted and kept OUT of the replay list, so the serve loop's
                // DOWN-then-UP ping-pong pairing sees exactly the stream a version-1 reel carried.
                if (kind != WireRecordSink.KIND_DOWN && kind != WireRecordSink.KIND_UP) {
                    r.unknownKinds++;
                    continue;
                }
                r.records.add(new Rec((byte) kind, tick, millis, payload));
                if (kind == WireRecordSink.KIND_DOWN) {
                    r.downRecords++;
                    r.downBytes += payload.length;
                    downDigest.update(payload);
                    if (r.firstDownMillis < 0) {
                        r.firstDownMillis = millis;
                    }
                    r.lastDownMillis = millis;
                } else {
                    r.upRecords++;
                }
            }
        }
        r.downSha256 = toHex(downDigest.digest());
        if (r.unknownKinds > 0) {
            System.out.println("replay playback: skipped unknown_kinds=" + r.unknownKinds
                    + " (record kinds other than DOWN/UP are not replayed)");
        }
        return r;
    }

    /** Full-decode gate (criterion 5): refuse any file the unchanged codecs cannot decode. */
    private static void requireVerified(String ccwrPath) throws IOException {
        WireRecordSink.Verified v = WireRecordSink.verify(ccwrPath);
        if (!v.error.isEmpty()) {
            throw new IOException("CCWR refused (detect-not-migrate): " + v.error);
        }
    }

    // ---------- serve ----------

    private static int serve(String[] args) {
        String ccwrPath = null, portFilePath = null, recordPath = null;
        Path receiptPath = null;
        double speed = 1.0;
        int awaitSeconds = 120;
        boolean offLoopback = false;
        Map<String, String> receipt = new LinkedHashMap<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--ccwr" -> ccwrPath = args[++i];
                    case "--port-file" -> portFilePath = args[++i];
                    case "--receipt" -> receiptPath = Path.of(args[++i]);
                    case "--record" -> recordPath = args[++i];
                    case "--speed" -> speed = Double.parseDouble(args[++i]);
                    case "--await-seconds" -> awaitSeconds = Integer.parseInt(args[++i]);
                    case "--off-loopback" -> offLoopback = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (ccwrPath == null || portFilePath == null || receiptPath == null) {
                throw new IllegalArgumentException(
                        "required: serve --ccwr <path> --port-file <path> --receipt <path>"
                                + " [--speed D] [--await-seconds N] [--record <path>] [--off-loopback]");
            }
            // Detect-not-migrate BEFORE any socket/port-file side effect.
            requireVerified(ccwrPath);
            Recording rec = load(ccwrPath);
            if (rec.downRecords == 0) {
                throw new IOException("recording has no DOWN records: " + ccwrPath);
            }
            receipt.put("ccwr", ccwrPath.replace("\\", "\\\\"));
            receipt.put("down_records", Long.toString(rec.downRecords));
            receipt.put("up_records", Long.toString(rec.upRecords));
            receipt.put("down_bytes", Long.toString(rec.downBytes));
            receipt.put("down_sha256", rec.downSha256);
            long recordedSpan = rec.lastDownMillis - rec.firstDownMillis;
            receipt.put("recorded_span_millis", Long.toString(recordedSpan));
            receipt.put("speed", Double.toString(speed));
            receipt.put("truncated_tail", Boolean.toString(rec.truncatedTail));

            InetAddress bind = offLoopback ? firstNonLoopbackAddress() : InetAddress.getLoopbackAddress();
            if (bind == null) {
                throw new IOException("--off-loopback: no site-local IPv4 interface found");
            }
            try (ServerSocket serverSocket = new ServerSocket()) {
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(bind, 0));
                serverSocket.setSoTimeout(awaitSeconds * 1000);
                PilotPortFile.write(portFilePath, bind.getHostAddress(), serverSocket.getLocalPort());
                System.out.println("replay playback server: serving " + ccwrPath
                        + " on " + bind.getHostAddress() + ":" + serverSocket.getLocalPort()
                        + " speed=" + speed + " (port file " + portFilePath + ")");
                try (Socket client = serverSocket.accept()) {
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                    int code = speak(rec, client, recordPath, ccwrPath, speed, receipt);
                    writeReceipt(receiptPath, receipt, "");
                    System.out.println("replay playback server OK: down_sent=" + receipt.get("down_sent")
                            + " up_read=" + receipt.get("up_read")
                            + " sent_sha256=" + receipt.get("sent_sha256")
                            + " served_span_millis=" + receipt.get("served_span_millis"));
                    return code;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (receiptPath != null) {
                try {
                    writeReceipt(receiptPath, receipt,
                            e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
                } catch (IOException ignore) {
                    // best effort
                }
            }
            return 1;
        }
    }

    /** Re-speak the recording to one connected client; fills the receipt; 0 = full file served. */
    private static int speak(Recording rec, Socket client, String recordPath, String ccwrPath,
            double speed, Map<String, String> receipt) throws IOException {
        OutputStream out = new BufferedOutputStream(client.getOutputStream());
        InputStream in = new BufferedInputStream(client.getInputStream());
        MessageDigest sentDigest = newSha256();
        WireRecordSink sink = null;
        long downSent = 0, upRead = 0, bytesSent = 0, upSkipped = 0;
        boolean clientClosedEarly = false;
        long serveStartNanos = -1, lastActionNanos = -1;
        try {
            if (recordPath != null) {
                // re-speak records carry the same record-completeness meta as a live run
                // (schema versions + re-sim identity) plus the replay-specific provenance keys.
                Map<String, String> meta = new LinkedHashMap<>();
                meta.put("mode", "replay-playback");
                meta.put("source_ccwr", ccwrPath);
                meta.putAll(MatchRecordMeta.base());
                sink = WireRecordSink.open(recordPath, meta);
            }
            int i = 0;
            while (i < rec.records.size()) {
                Rec r = rec.records.get(i);
                if (r.kind() != WireRecordSink.KIND_DOWN) {
                    // An UP with no preceding DOWN (never produced by the live loop; tolerated).
                    upSkipped++;
                    i++;
                    continue;
                }
                // The re-emission pacer: absolute schedule against the recording's own epoch-millis
                // timeline (no cumulative drift; the UP-read time is absorbed automatically).
                if (serveStartNanos < 0) {
                    serveStartNanos = System.nanoTime();
                } else if (speed > 0 && rec.firstDownMillis >= 0 && r.millis() >= rec.firstDownMillis) {
                    long targetNanos = serveStartNanos
                            + (long) ((r.millis() - rec.firstDownMillis) / speed * 1_000_000.0);
                    long sleepMillis = (targetNanos - System.nanoTime()) / 1_000_000L;
                    if (sleepMillis > 0) {
                        try {
                            Thread.sleep(sleepMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("pacer interrupted", ie);
                        }
                    }
                }
                out.write(r.payload());
                out.flush();
                sentDigest.update(r.payload());
                downSent++;
                bytesSent += r.payload().length;
                if (sink != null) {
                    sink.tick(r.tick());
                    sink.record(WireRecordSink.KIND_DOWN, r.payload());
                }
                lastActionNanos = System.nanoTime();
                i++;
                if (i < rec.records.size() && rec.records.get(i).kind() == WireRecordSink.KIND_UP) {
                    // The recorded conversation read one intent here — hold the same ping-pong.
                    PilotIntentFrame intent = PilotIntentWireCodec.readFrame(in);
                    if (intent == null) {
                        clientClosedEarly = true;
                        break;
                    }
                    upRead++;
                    if (sink != null) {
                        ByteArrayOutputStream cap = new ByteArrayOutputStream(PilotIntentWireCodec.FRAME_BYTES);
                        PilotIntentWireCodec.writeFrame(cap, intent);
                        sink.tick(rec.records.get(i).tick());
                        sink.record(WireRecordSink.KIND_UP, cap.toByteArray());
                    }
                    lastActionNanos = System.nanoTime();
                    i++;
                }
            }
        } finally {
            if (sink != null) {
                sink.close();
                receipt.put("record_path", sink.path().replace("\\", "\\\\"));
                receipt.put("record_error", sink.error());
                receipt.put("record_down", Long.toString(sink.recordsDown()));
                receipt.put("record_up", Long.toString(sink.recordsUp()));
            }
        }
        long servedSpanMillis = (serveStartNanos < 0 || lastActionNanos < 0)
                ? 0 : (lastActionNanos - serveStartNanos) / 1_000_000L;
        receipt.put("down_sent", Long.toString(downSent));
        receipt.put("up_read", Long.toString(upRead));
        receipt.put("up_skipped", Long.toString(upSkipped));
        receipt.put("bytes_sent", Long.toString(bytesSent));
        receipt.put("sent_sha256", toHex(sentDigest.digest()));
        receipt.put("served_span_millis", Long.toString(servedSpanMillis));
        receipt.put("client_closed_early", Boolean.toString(clientClosedEarly));
        return clientClosedEarly ? 3 : 0;
    }

    // ---------- verify-client ----------

    /** Counts + digests every byte that crosses up from the socket (read-ahead included at EOF). */
    private static final class CountingDigestInputStream extends FilterInputStream {
        final MessageDigest digest;
        long count;

        CountingDigestInputStream(InputStream in, MessageDigest digest) {
            super(in);
            this.digest = digest;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                digest.update((byte) b);
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                digest.update(b, off, n);
                count += n;
            }
            return n;
        }
    }

    private static int verifyClient(String[] args) {
        String portFilePath = null;
        Path receiptPath = null;
        long shipId = 1L;
        long maxExchanges = 1_000_000L;
        int portFileWaitSeconds = 20;
        Map<String, String> receipt = new LinkedHashMap<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port-file" -> portFilePath = args[++i];
                    case "--receipt" -> receiptPath = Path.of(args[++i]);
                    case "--ship-id" -> shipId = Long.parseLong(args[++i]);
                    case "--max-exchanges" -> maxExchanges = Long.parseLong(args[++i]);
                    case "--port-file-wait-seconds" -> portFileWaitSeconds = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (portFilePath == null || receiptPath == null) {
                throw new IllegalArgumentException(
                        "required: verify-client --port-file <path> --receipt <path>"
                                + " [--ship-id N] [--max-exchanges N] [--port-file-wait-seconds N]");
            }
            // Criterion 1: the endpoint comes from the handshake file ALONE.
            String[] endpoint = null;
            long deadline = System.currentTimeMillis() + portFileWaitSeconds * 1000L;
            while (endpoint == null && System.currentTimeMillis() < deadline) {
                endpoint = PilotPortFile.read(portFilePath);
                if (endpoint == null) {
                    Thread.sleep(PORT_FILE_POLL_MILLIS);
                }
            }
            if (endpoint == null) {
                throw new IOException("port file never became readable: " + portFilePath);
            }
            String host = endpoint[0];
            int port = Integer.parseInt(endpoint[1]);
            receipt.put("host", host);
            receipt.put("port", Integer.toString(port));

            MessageDigest digest = newSha256();
            long exchanges = 0, gpeeFrames = 0, awssFrames = 0, pwvwFrames = 0, intentsSent = 0;
            boolean terminalSeen = false, cleanEof = false;
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS * 5);
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                CountingDigestInputStream counting =
                        new CountingDigestInputStream(socket.getInputStream(), digest);
                BufferedInputStream in = new BufferedInputStream(counting);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                while (exchanges < maxExchanges) {
                    int magic = peekMagic(in);
                    if (magic < 0) {
                        cleanEof = true;
                        break;
                    }
                    int frameSeq;
                    if (magic == GameplayEdgeEventCodec.MAGIC) {
                        GameplayEdgeEventCodec.readEvent(in);
                        gpeeFrames++;
                        continue;
                    }
                    if (magic == AuthoritativeWorldSnapshotCodec.MAGIC) {
                        byte[] frameBytes = AuthoritativeWorldSnapshotCodec.readFrameBytes(in);
                        if (frameBytes == null) {
                            cleanEof = true;
                            break;
                        }
                        List<cc.starsector.server.protocol.AuthoritativeWorldSnapshot> decoded =
                                AuthoritativeWorldSnapshotCodec.readSnapshots(
                                        new java.io.ByteArrayInputStream(frameBytes));
                        if (decoded.size() != 1) {
                            throw new IOException("AWSS frame decoded to " + decoded.size() + " snapshots");
                        }
                        frameSeq = (int) Math.max(0L, decoded.get(0).simTick());
                        awssFrames++;
                    } else if (magic == PilotWorldViewWireCodec.MAGIC) {
                        PilotWorldViewFrame view = PilotWorldViewWireCodec.readFrame(in);
                        if (view == null) {
                            cleanEof = true;
                            break;
                        }
                        pwvwFrames++;
                        if (view.terminal()) {
                            terminalSeen = true;
                            break;
                        }
                        frameSeq = Math.max(0, view.frameSeq());
                    } else {
                        throw new IOException("unknown inner magic 0x" + Integer.toHexString(magic));
                    }
                    exchanges++;
                    PilotIntentFrame neutral =
                            PilotIntentFrame.of(shipId, frameSeq, 0f, 0, false, 0, 0f, 0f, 0f);
                    PilotIntentWireCodec.writeFrame(out, neutral);
                    out.flush();
                    intentsSent++;
                }
                receipt.put("received_bytes", Long.toString(counting.count));
            }
            receipt.put("received_sha256", toHex(digest.digest()));
            receipt.put("exchanges", Long.toString(exchanges));
            receipt.put("gpee_frames", Long.toString(gpeeFrames));
            receipt.put("awss_frames", Long.toString(awssFrames));
            receipt.put("pwvw_frames", Long.toString(pwvwFrames));
            receipt.put("intents_sent", Long.toString(intentsSent));
            receipt.put("terminal_seen", Boolean.toString(terminalSeen));
            receipt.put("clean_eof", Boolean.toString(cleanEof));
            writeReceipt(receiptPath, receipt, "");
            System.out.println("replay verify client OK: exchanges=" + exchanges
                    + " received_sha256=" + receipt.get("received_sha256"));
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            if (receiptPath != null) {
                try {
                    writeReceipt(receiptPath, receipt,
                            e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
                } catch (IOException ignore) {
                    // best effort
                }
            }
            return 1;
        }
    }

    // ---------- digest ----------

    private static int digest(String[] args) {
        try {
            String ccwrPath = null;
            for (int i = 0; i < args.length; i++) {
                if ("--ccwr".equals(args[i])) {
                    ccwrPath = args[++i];
                } else {
                    throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (ccwrPath == null) {
                throw new IllegalArgumentException("required: digest --ccwr <path>");
            }
            WireRecordSink.Verified v = WireRecordSink.verify(ccwrPath);
            System.out.println("verify_error=" + v.error);
            Recording rec = load(ccwrPath);
            System.out.println("down_records=" + rec.downRecords);
            System.out.println("up_records=" + rec.upRecords);
            System.out.println("down_bytes=" + rec.downBytes);
            System.out.println("down_sha256=" + rec.downSha256);
            System.out.println("recorded_span_millis=" + (rec.lastDownMillis - rec.firstDownMillis));
            System.out.println("truncated_tail=" + rec.truncatedTail);
            for (Map.Entry<String, String> e : rec.meta.entrySet()) {
                System.out.println("meta_" + e.getKey() + "=" + e.getValue());
            }
            printPoseExtremes(rec);
            return v.error.isEmpty() ? 0 : 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * file-side POSE EXTREMES: demux every DOWN payload with the live client's
     * shape (GPEE routed, AWSS decoded, PWVW honored) and print per-entity min/max loc plus
     * engine-state record counts. Motive: the replay-fed bench arena must be sized at least as
     * large as the recorded map or foe-ghost corrections land outside it (the engine's edge
     * logic then ends the fight — the RUN 3 spurious {@code isCombatOver}). Fail-soft: a decode
     * surprise prints {@code pose_walk_error} and leaves the base digest output intact.
     */
    private static void printPoseExtremes(Recording rec) {
        try {
            long gpeeFrames = 0, awssFrames = 0, pwvwFrames = 0, engineStateRecords = 0;
            Map<Long, float[]> poses = new LinkedHashMap<>(); // entityId -> minX,maxX,minY,maxY,records
            Map<Long, long[]> engineStates = new LinkedHashMap<>(); // entityId -> records,nonzeroDisabled,controllerNonActive
            for (Rec r : rec.records) {
                if (r.kind() != WireRecordSink.KIND_DOWN) {
                    continue;
                }
                BufferedInputStream in = new BufferedInputStream(
                        new java.io.ByteArrayInputStream(r.payload()));
                while (true) {
                    int magic = peekMagic(in);
                    if (magic < 0) {
                        break;
                    }
                    if (magic == GameplayEdgeEventCodec.MAGIC) {
                        GameplayEdgeEventCodec.readEvent(in);
                        gpeeFrames++;
                    } else if (magic == AuthoritativeWorldSnapshotCodec.MAGIC) {
                        byte[] frameBytes = AuthoritativeWorldSnapshotCodec.readFrameBytes(in);
                        if (frameBytes == null) {
                            break;
                        }
                        awssFrames++;
                        for (cc.starsector.server.protocol.AuthoritativeWorldSnapshot snap
                                : AuthoritativeWorldSnapshotCodec.readSnapshots(
                                        new java.io.ByteArrayInputStream(frameBytes))) {
                            for (cc.starsector.server.protocol.AuthoritativeEntitySnapshot ent
                                    : snap.entities()) {
                                float[] p = poses.computeIfAbsent(ent.entityId(), k -> new float[] {
                                        Float.MAX_VALUE, -Float.MAX_VALUE,
                                        Float.MAX_VALUE, -Float.MAX_VALUE, 0f });
                                p[0] = Math.min(p[0], ent.locX());
                                p[1] = Math.max(p[1], ent.locX());
                                p[2] = Math.min(p[2], ent.locY());
                                p[3] = Math.max(p[3], ent.locY());
                                p[4]++;
                            }
                            for (cc.starsector.server.protocol.ShipEngineStateSnapshot es
                                    : snap.engineStates()) {
                                engineStateRecords++;
                                long[] s = engineStates.computeIfAbsent(es.entityId(),
                                        k -> new long[3]);
                                s[0]++;
                                if (es.disabledMask() != 0 || es.permDisabledMask() != 0) {
                                    s[1]++;
                                }
                                if (es.controllerState()
                                        != cc.starsector.server.protocol.ShipEngineStateSnapshot.CONTROLLER_ACTIVE) {
                                    s[2]++;
                                }
                            }
                        }
                    } else if (magic == PilotWorldViewWireCodec.MAGIC) {
                        if (PilotWorldViewWireCodec.readFrame(in) == null) {
                            break;
                        }
                        pwvwFrames++;
                    } else {
                        throw new IOException("unknown inner magic 0x" + Integer.toHexString(magic));
                    }
                }
            }
            System.out.println("gpee_frames=" + gpeeFrames);
            System.out.println("awss_frames=" + awssFrames);
            System.out.println("pwvw_frames=" + pwvwFrames);
            System.out.println("engine_state_records=" + engineStateRecords);
            System.out.println("pose_entities=" + poses.size());
            for (Map.Entry<Long, float[]> e : poses.entrySet()) {
                float[] p = e.getValue();
                System.out.println("pose_entity_" + e.getKey()
                        + "=minX:" + p[0] + ",maxX:" + p[1]
                        + ",minY:" + p[2] + ",maxY:" + p[3]
                        + ",records:" + (long) p[4]);
            }
            for (Map.Entry<Long, long[]> e : engineStates.entrySet()) {
                long[] s = e.getValue();
                System.out.println("engine_state_entity_" + e.getKey()
                        + "=records:" + s[0] + ",nonzeroDisabled:" + s[1]
                        + ",controllerNonActive:" + s[2]);
            }
        } catch (Exception e) {
            System.out.println("pose_walk_error=" + e.getClass().getName() + ": "
                    + (e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    // ---------- shared helpers ----------

    /** Peek the next frame's 4-byte magic without consuming it, or -1 at end-of-stream. */
    private static int peekMagic(BufferedInputStream in) throws IOException {
        in.mark(4);
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        in.reset();
        if ((b0 | b1 | b2 | b3) < 0) {
            return -1;
        }
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static InetAddress firstNonLoopbackAddress() throws IOException {
        Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
        while (ifs != null && ifs.hasMoreElements()) {
            NetworkInterface ni = ifs.nextElement();
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (!a.isLoopbackAddress() && a instanceof Inet4Address && a.isSiteLocalAddress()) {
                    return a;
                }
            }
        }
        return null;
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

    private static void writeReceipt(Path receiptPath, Map<String, String> fields, String error)
            throws IOException {
        Path parent = receiptPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            lines.add(e.getKey() + "=" + e.getValue());
        }
        lines.add("error=" + error);
        Files.write(receiptPath, lines,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
