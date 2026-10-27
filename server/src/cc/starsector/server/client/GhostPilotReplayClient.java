package cc.starsector.server.client;

import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.runtime.AuthoritativeWorldSnapshotCodec;
import cc.starsector.server.runtime.CcwrUpReplayReader;
import cc.starsector.server.runtime.GameplayEdgeEventCodec;
import cc.starsector.server.runtime.PilotIntentFrame;
import cc.starsector.server.runtime.PilotIntentWireCodec;
import cc.starsector.server.runtime.PilotPortFile;
import cc.starsector.server.runtime.PilotWorldViewFrame;
import cc.starsector.server.runtime.PilotWorldViewWireCodec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>GHOST second client</b> (the
 * recorded-ghost variant): a separate JVM that replays a CCWR recording's UP
 * {@link PilotIntentFrame}s — a human's real recorded predict-lane inputs — as ship 2's intent
 * stream, so one human can fight "their last round's self" through the unchanged two-endpoint
 * server topology.
 *
 * <p>Per tick (strict ping-pong, the {@link PilotIntentSocketClient} contract): read the server's
 * DOWN frame (demux by magic — {@code GPEE} decode-and-discard, {@code AWSS} consume {@code simTick},
 * terminal {@code PWVW} stop), then send the NEXT recorded UP frame remapped: {@code shipId} → the
 * assigned ghost ship id and {@code frameSeq} → the server tick just read (so the gate's
 * {@code decoded2.frameSeq() == i} check holds). The recorded aim/keys/fire/facing bytes are carried
 * verbatim — a real human produced them. After the recording is exhausted the ghost sends NEUTRAL
 * frames (no keys, no fire, both facings held at the last recorded values) — the honest "ghost
 * finished its round", not a loop and not an invented input. Keeps a SHA-256 of exactly the sent
 * bytes (receipt {@code sent_sha256}) so the server proves byte-identity end to end.
 *
 * <p>Fail-closed like every client here: any error exits non-zero with the receipt marked
 * {@code connected=false} / the error recorded.
 */
public final class GhostPilotReplayClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    // Generous first-read budget: in a real WATCH the human-paced deploy sits between the ghost's
    // connect and the server's first loop tick (the starvation shape on endpoint 2).
    private static final int SOCKET_TIMEOUT_MILLIS = 300_000;
    private static final int DEFAULT_MAX_FRAMES = 100_000;

    private GhostPilotReplayClient() {
    }

    public static void main(String[] args) {
        String host = null;
        int port = -1;
        long shipId = -1L;
        String ccwrPath = null;
        int maxFrames = DEFAULT_MAX_FRAMES;
        int skipFrames = 0;
        Path receiptPath = null;
        try {
            String portFile = null;
            for (int i = 0; i + 1 < args.length; i += 2) {
                switch (args[i]) {
                    case "--host" -> host = args[i + 1];
                    case "--port" -> port = Integer.parseInt(args[i + 1]);
                    case "--port-file" -> portFile = args[i + 1];
                    case "--ccwr" -> ccwrPath = args[i + 1];
                    case "--ship-id" -> shipId = Long.parseLong(args[i + 1]);
                    case "--max-frames" -> maxFrames = Integer.parseInt(args[i + 1]);
                    case "--skip" -> skipFrames = Integer.parseInt(args[i + 1]);
                    case "--receipt" -> receiptPath = Path.of(args[i + 1]);
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
            if (host == null || port < 0 || shipId < 0L || ccwrPath == null || receiptPath == null) {
                throw new IllegalArgumentException(
                        "required: (--host --port | --port-file) --ccwr --ship-id --receipt"
                                + " [--max-frames] [--skip]");
            }
            CcwrUpReplayReader.Replay replay = CcwrUpReplayReader.read(ccwrPath);
            if (!replay.error.isEmpty()) {
                throw new IOException("CCWR decode failed: " + replay.error);
            }
            if (replay.frames.isEmpty()) {
                throw new IOException("CCWR recording has no UP frames: " + ccwrPath);
            }
            if (skipFrames < 0 || skipFrames >= replay.frames.size()) {
                throw new IllegalArgumentException("--skip " + skipFrames
                        + " out of range for a " + replay.frames.size() + "-frame recording");
            }
            Result result = stream(host, port, shipId, replay.frames, skipFrames, maxFrames);
            writeReceipt(receiptPath, host, port, shipId, ccwrPath, replay.frames.size(), skipFrames,
                    true, result, "");
            System.out.println("ghost pilot replay client OK: frames_sent=" + result.framesSent
                    + " replay_frames_sent=" + result.replayFramesSent
                    + " neutral_frames_sent=" + result.neutralFramesSent
                    + " sent_sha256=" + result.sentSha256);
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            if (receiptPath != null) {
                try {
                    writeReceipt(receiptPath, host, port, shipId, ccwrPath, -1, -1, false,
                            new Result(0, 0, 0, 0, ""),
                            e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
                } catch (IOException ignore) {
                    // best effort
                }
            }
            System.exit(1);
        }
    }

    private record Result(
            int framesSent, int replayFramesSent, int neutralFramesSent, int gpeeFramesSkipped,
            String sentSha256) {
    }

    /** Connect, then per tick: DOWN demux, UP = next recorded frame remapped (NEUTRAL after exhaustion). */
    private static Result stream(
            String host, int port, long shipId, List<CcwrUpReplayReader.TickFrame> recording,
            int skipFrames, int maxFrames)
            throws IOException {
        MessageDigest digest = newSha256();
        int framesSent = 0, replayFramesSent = 0, neutralFramesSent = 0, gpeeFramesSkipped = 0;
        // --skip starts the replay mid-recording (e.g. at the first fire window) — still the human's
        // real recorded stream from that point on, just a later round entry, not an invented input.
        int cursor = skipFrames;
        // NEUTRAL frames hold the LAST recorded facings so the ghost ship coasts on its final heading
        // (PilotIntentFrame requires finite facings; 0 would snap the hull to east, not "stop inputs").
        float lastCursorFacingDeg = recording.get(recording.size() - 1).frame().cursorFacingDeg();
        float lastOwnFacingDeg = recording.get(recording.size() - 1).frame().ownShipFacingDeg();
        int lastSelectedGroup = recording.get(recording.size() - 1).frame().selectedWeaponGroup();
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            while (framesSent < maxFrames) {
                int magic = peekMagic(in);
                if (magic < 0) {
                    break; // clean EOF — the predict lane's stop signal (server closed after teardown)
                }
                int frameSeq;
                if (magic == GameplayEdgeEventCodec.MAGIC) {
                    // The gate's ghost exchange is the 1-arg (no-GPEE) path; decode-and-discard anyway so
                    // a future edge-lane ghost feed cannot kill the replay (same demux as the live client).
                    GameplayEdgeEventCodec.readEvent(in);
                    gpeeFramesSkipped++;
                    continue;
                }
                if (magic == AuthoritativeWorldSnapshotCodec.MAGIC) {
                    byte[] frameBytes = AuthoritativeWorldSnapshotCodec.readFrameBytes(in);
                    if (frameBytes == null) {
                        break;
                    }
                    List<AuthoritativeWorldSnapshot> decoded =
                            AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(frameBytes));
                    if (decoded.isEmpty()) {
                        break;
                    }
                    frameSeq = (int) decoded.get(0).simTick();
                } else {
                    PilotWorldViewFrame view = PilotWorldViewWireCodec.readFrame(in);
                    if (view == null || view.terminal()) {
                        break; // terminal PWVW — the server signalled done
                    }
                    // Non-terminal PWVW (e.g. an endpoint-2 keep-warm): still a ping-pong — answer it.
                    frameSeq = view.frameSeq();
                }
                PilotIntentFrame intent;
                if (cursor < recording.size()) {
                    PilotIntentFrame rec = recording.get(cursor++).frame();
                    intent = PilotIntentFrame.of(
                            shipId, frameSeq, rec.cursorFacingDeg(), rec.heldControls(), rec.fireHeld(),
                            rec.selectedWeaponGroup(), rec.ownShipFacingDeg(),
                            rec.aimWorldX(), rec.aimWorldY());
                    lastCursorFacingDeg = rec.cursorFacingDeg();
                    lastOwnFacingDeg = rec.ownShipFacingDeg();
                    lastSelectedGroup = rec.selectedWeaponGroup();
                    replayFramesSent++;
                } else {
                    intent = PilotIntentFrame.of(
                            shipId, frameSeq, lastCursorFacingDeg, 0, false,
                            lastSelectedGroup, lastOwnFacingDeg, 0f, 0f);
                    neutralFramesSent++;
                }
                ByteArrayOutputStream frameBytes = new ByteArrayOutputStream(PilotIntentWireCodec.FRAME_BYTES);
                PilotIntentWireCodec.writeFrame(frameBytes, intent);
                byte[] bytes = frameBytes.toByteArray();
                digest.update(bytes);
                out.write(bytes);
                out.flush();
                framesSent++;
            }
        }
        return new Result(framesSent, replayFramesSent, neutralFramesSent, gpeeFramesSkipped, toHex(digest.digest()));
    }

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

    private static void writeReceipt(
            Path receiptPath, String host, int port, long shipId, String ccwrPath, int recordingFrames,
            int skipFrames, boolean connected, Result result, String error) throws IOException {
        Path parent = receiptPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        lines.add("client_class=" + GhostPilotReplayClient.class.getName());
        lines.add("host=" + (host == null ? "" : host));
        lines.add("port=" + port);
        lines.add("source_ship_id=" + shipId);
        lines.add("ccwr=" + (ccwrPath == null ? "" : ccwrPath.replace("\\", "\\\\")));
        lines.add("recording_frames=" + recordingFrames);
        lines.add("skip_frames=" + skipFrames);
        lines.add("connected=" + connected);
        lines.add("frames_sent=" + result.framesSent());
        lines.add("replay_frames_sent=" + result.replayFramesSent());
        lines.add("neutral_frames_sent=" + result.neutralFramesSent());
        lines.add("gpee_frames_skipped=" + result.gpeeFramesSkipped());
        lines.add("sent_sha256=" + result.sentSha256());
        lines.add("error=" + error);
        Files.write(receiptPath, lines,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
