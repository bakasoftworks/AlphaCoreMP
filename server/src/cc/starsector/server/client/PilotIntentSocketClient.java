package cc.starsector.server.client;

import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.runtime.AuthoritativeWorldSnapshotCodec;
import cc.starsector.server.runtime.FireDischargeDeclaration;
import cc.starsector.server.runtime.FireDischargeDeclarationCodec;
import cc.starsector.server.runtime.GameplayEdgeEventCodec;
import cc.starsector.server.runtime.PilotIntentFrame;
import cc.starsector.server.runtime.PilotIntentWireCodec;
import cc.starsector.server.runtime.PilotPortFile;
import cc.starsector.server.runtime.PilotWorldViewFrame;
import cc.starsector.server.runtime.PilotWorldViewWireCodec;
import cc.starsector.server.runtime.SeatNameFrameCodec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * The separate remote pilot client process for the live two-client 1v1
 * — the promotion of {@link cc.starsector.server.runtime.PilotIntentSocketBridge}'s WRITE end
 * into its own JVM. A real second program (no engine, no shared memory) connects to the server's
 * {@link cc.starsector.server.runtime.PilotIntentSocketServer}, and each tick:
 *
 * <ol>
 *   <li>reads a {@link PilotWorldViewFrame} the server streamed DOWN (the two ship poses);</li>
 *   <li>computes its OWN faithful tracking aim ({@link PilotWorldViewFrame#bearingToTargetDeg()} — the
 *       cursor pointed at the enemy it perceives), its scripted strafe-then-settle key (STRAFE_LEFT for
 *       the burst, then released), and a held fire button;</li>
 *   <li>streams a full {@link PilotIntentFrame} back UP the unchanged {@link PilotIntentWireCodec}.</li>
 * </ol>
 *
 * <p>So the faithful pilot intent the server applies to the engine is produced by an actual remote
 * peer, not round-tripped in one JVM. The aim is the client's (computed from the replicated poses),
 * which keeps it faithful for a moving platform without a recorded/scripted-aim fake. The client keeps
 * a SHA-256 of exactly the intent bytes it sent so the server can prove byte-identity end to end.
 *
 * <p>Fail-closed: any error exits non-zero with a stack trace on stderr (the launcher redirects it to a
 * log) and writes a receipt marked {@code connected=false} / with the error.
 *
 * <p><b>{@code --intent-hz} duplex mode (an A/B fairness fix, opt-in):</b> the default
 * loop above is PING-PONG — one intent per DOWN frame — which serializes the client's UP cadence
 * onto the DOWN round trip; paired against the clock-driven {@link PilotIntentDatagramClient} that
 * rebuilds the v1 RTT-serialization artifact the A/B's D2 guard forbids crediting UDP for.
 * {@code --intent-hz N} (default 0 = ping-pong, byte-identical for every pre-existing caller)
 * instead runs a reader thread that drains DOWN (magic dispatch unchanged, publishes the latest
 * simTick/aim) while the MAIN thread clocks intents UP at N Hz echoing the latest-seen simTick
 * (the explicit-echoTick posture; the datagram client's exact loop shape). The sent-SHA
 * stays a digest over the intent bytes handed to the wire; exit on DOWN EOF/terminal, a terminal
 * reader failure (the 60 s read timeout), or the frame cap.
 */
public final class PilotIntentSocketClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 60_000;
    private static final int DEFAULT_MAX_FRAMES = 100_000;

    private PilotIntentSocketClient() {
    }

    public static void main(String[] args) {
        String host = null;
        int port = -1;
        long shipId = -1L;
        int selectedGroup = 0;
        int strafeBurst = 0;
        int ventFrom = 0;
        int fireFrom = 0;
        int systemFrom = 0;
        int declEvery = 0;
        int maxFrames = DEFAULT_MAX_FRAMES;
        int intentHz = 0;
        String announceName = "";
        Path receiptPath = null;
        try {
            String portFile = null;
            for (int i = 0; i + 1 < args.length; i += 2) {
                switch (args[i]) {
                    case "--host" -> host = args[i + 1];
                    case "--port" -> port = Integer.parseInt(args[i + 1]);
                    // Watch self-test: resolve the endpoint from the SAME port-file the game
                    // window's PilotIntentCombatPlugin reads (via PilotPortFile, the shared contract), so
                    // the headless self-test exercises the watch-mode endpoint advertisement path, not
                    // just an explicitly-passed host/port. --host/--port still win if also given.
                    case "--port-file" -> portFile = args[i + 1];
                    case "--ship-id" -> shipId = Long.parseLong(args[i + 1]);
                    case "--selected-group" -> selectedGroup = Integer.parseInt(args[i + 1]);
                    case "--strafe-burst" -> strafeBurst = Integer.parseInt(args[i + 1]);
                    // gap-⑤ probe lane: hold the VENT_FLUX bit (heldControls bit 8) from this
                    // frameSeq onward (0 = never, the default — every pre-existing lane's bytes are unchanged).
                    // One clean rising edge for the server's faithful giveCommand(VENT_FLUX) replay to act on.
                    case "--vent-from" -> ventFrom = Integer.parseInt(args[i + 1]);
                    // flux-rise measurement: hold the fire trigger (fireHeld) from this frameSeq
                    // onward. Default 0 = fire from frame 0 = the always-firing behavior every pre-existing
                    // caller (the puppet self-test, the steps-1-4 measurement) relied on. A POSITIVE value
                    // leaves a no-fire baseline window [0, fireFrom) so the measurement can observe the
                    // owned ship's flux RISE + its zero-flux-boost maxSpeed cap DROP across the fire edge.
                    case "--fire-from" -> fireFrom = Integer.parseInt(args[i + 1]);
                    // system-engage measurement: HOLD the ship-system bit (heldControls bit 7,
                    // PilotIntentFrame.USE_SHIP_SYSTEM == 1<<7 == WIRE_BIT_USE_SYSTEM) from this frameSeq onward
                    // (0 = never, the default — every pre-existing lane's bytes are unchanged). The predict client
                    // sets bit 7 while its OWN system isActive(); this synthetic pilot drives it as a continuous
                    // HOLD so the server's held-driven applySystemBit must RE-engage the owned ship's system each
                    // cooldown cycle (a one-shot rising edge would only show the FIRST activation — the measured
                    // residual the held-driven fix closes is the LATE re-engagement, observed in the second half).
                    case "--system-from" -> systemFrom = Integer.parseInt(args[i + 1]);
                    // The declared-discharge lane (selftest live extension): every N frames (0 = never,
                    // the default — every pre-existing lane's bytes are unchanged) write one PDCL declaration
                    // frame AFTER the intent frame within the same tick, exercising the server's up-lane
                    // PINT/PDCL demux across a real socket. The declaration bytes are NOT folded into the
                    // sent-SHA digest (the server digests re-encoded INTENTS only, so byte-identity still
                    // proves the intent lane untouched).
                    case "--decl-every" -> declEvery = Integer.parseInt(args[i + 1]);
                    // Duplex mode (see the class doc): 0 (default) = the byte-identical
                    // ping-pong loop; N>0 = clock-driven UP at N Hz with a DOWN-draining reader thread.
                    case "--intent-hz" -> intentHz = Integer.parseInt(args[i + 1]);
                    case "--max-frames" -> maxFrames = Integer.parseInt(args[i + 1]);
                    case "--receipt" -> receiptPath = Path.of(args[i + 1]);
                    // The seat-name posture (duplex-only, opt-in): announce a seat name (SNAM) right
                    // after connect — the retired serve() lane (git history) with the event lane
                    // ON blocked its SNAM window (clientWaitMillis) until an announce arrived or
                    // the window lapsed. Refused
                    // with --intent-hz 0: the ping-pong lane's bytes are a standing regression bar
                    // (pair-crash-drill + the watch selftest), never changed by a stray flag.
                    case "--announce-name" -> announceName = args[i + 1];
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
                throw new IllegalArgumentException(
                        "required: (--host --port | --port-file) --ship-id --receipt [--selected-group --strafe-burst --vent-from --fire-from --system-from --decl-every --intent-hz --max-frames]");
            }
            if (intentHz < 0 || intentHz > 1_000) {
                throw new IllegalArgumentException(
                        "--intent-hz must be 0 (ping-pong) or 1..1000 (duplex): " + intentHz);
            }
            if (!announceName.isEmpty() && intentHz == 0) {
                throw new IllegalArgumentException(
                        "--announce-name requires duplex mode (--intent-hz > 0); the ping-pong"
                                + " lane's bytes are a standing regression bar");
            }
            Result result = intentHz > 0
                    ? streamDuplex(
                            host, port, shipId, selectedGroup, strafeBurst, ventFrom, fireFrom, systemFrom,
                            declEvery, maxFrames, intentHz, announceName)
                    : stream(
                            host, port, shipId, selectedGroup, strafeBurst, ventFrom, fireFrom, systemFrom,
                            declEvery, maxFrames);
            writeReceipt(receiptPath, host, port, shipId, true, result, "");
            System.out.println("pilot-intent socket client OK: frames_sent=" + result.framesSent
                    + " last_aim_deg=" + result.lastAimDeg + " sent_sha256=" + result.sentSha256);
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            if (receiptPath != null) {
                try {
                    writeReceipt(receiptPath, host, port, shipId, false,
                            new Result(0, Float.NaN, "", 0, 0, 0f, 0f, 0),
                            e.getClass().getName() + ": " + (e.getMessage() == null ? "" : e.getMessage()));
                } catch (IOException ignore) {
                    // best effort
                }
            }
            System.exit(1);
        }
    }

    private record Result(int framesSent, float lastAimDeg, String sentSha256, int gpeeFramesSkipped,
            // A trajectory measurement: the owned ship's trajectory as OBSERVED in the AWSS
            // the server streamed down, so the receipt independently proves the ship physically MOVED under
            // the commanded intent (the server-side movement_commands tally proves the command was issued;
            // these prove the engine ship responded). All decoded from the same AWSS own-entity the aim uses.
            int ownFramesObserved, float ownMaxDisp, float ownMaxSpeed,
            // Selftest live extension: how many PDCL declaration frames this client wrote
            // after its intent frames (--decl-every lane; 0 unless opted in).
            int declSent) {
    }

    /** Connect, then drive the per-tick ping-pong (read world view DOWN, write pilot intent UP) until terminal. */
    private static Result stream(
            String host, int port, long shipId, int selectedGroup, int strafeBurst, int ventFrom, int fireFrom,
            int systemFrom, int declEvery, int maxFrames)
            throws IOException {
        MessageDigest digest = newSha256();
        int framesSent = 0;
        int gpeeFramesSkipped = 0;
        int declSent = 0;
        int declSeq = 0;
        float lastAimDeg = Float.NaN;
        // Owned-ship trajectory observed in the AWSS (the movement proof; see the Result javadoc).
        int ownFramesObserved = 0;
        float ownFirstX = Float.NaN;
        float ownFirstY = Float.NaN;
        float ownMaxDisp = 0f;
        float ownMaxSpeed = 0f;
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            while (framesSent < maxFrames) {
                // STEP-15 probe fix: under -Dcc.predict.feed the server's DOWN payload is the full AWSS
                // (AuthoritativeWorldSnapshotCodec), not a PilotWorldViewFrame — the prior worldview-only read
                // died on its FIRST predict frame (bad magic 0x41575353 "AWSS", observed predict-probe-211603),
                // so the --vent-from lane could never reach the server game-free. Dispatch per-frame on the
                // peeked magic: AWSS frames are decoded like the real game-window client decodes them (own/foe
                // mapped by entityId, aim = own bearing onto the foe), everything else stays the byte-identical
                // worldview path the puppet self-test verifies.
                int magic = peekMagic(in);
                int frameSeq;
                float aimDeg;
                if (magic == GameplayEdgeEventCodec.MAGIC) {
                    // The live edge lane (cc.predict.eventReplay, ON in cc-server.properties since
                    // 2026-06-10) multiplexes GPEE frames BEFORE an edge tick's AWSS on the same stream. The
                    // game-window client demuxes them (PredictReconcileClientSession); this scripted peer
                    // decodes-and-DISCARDS so the GAME-FREE probe lane survives edge ticks — it used to die on
                    // the first Hellbore discharge (GPEE magic read as a corrupt world-view frame). No intent
                    // is sent for an event frame: the server reads ONE intent per exchangeSnapshot, answered
                    // when the AWSS that follows the events is decoded below.
                    GameplayEdgeEventCodec.readEvent(in);
                    gpeeFramesSkipped++;
                    continue;
                }
                if (magic == AuthoritativeWorldSnapshotCodec.MAGIC) {
                    byte[] frameBytes = AuthoritativeWorldSnapshotCodec.readFrameBytes(in);
                    if (frameBytes == null) {
                        break; // clean end of stream
                    }
                    List<AuthoritativeWorldSnapshot> decoded =
                            AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(frameBytes));
                    if (decoded.isEmpty()) {
                        break;
                    }
                    AuthoritativeWorldSnapshot snap = decoded.get(0);
                    frameSeq = (int) snap.simTick(); // the server's loop frame i; echoed back for decode-match
                    AuthoritativeEntitySnapshot own = null;
                    AuthoritativeEntitySnapshot foe = null;
                    for (AuthoritativeEntitySnapshot entity : snap.entities()) {
                        if (entity.entityId() == shipId) {
                            own = entity;
                        } else if (foe == null) {
                            foe = entity;
                        }
                    }
                    if (own != null) {
                        ownFramesObserved++;
                        if (Float.isNaN(ownFirstX)) {
                            ownFirstX = own.locX();
                            ownFirstY = own.locY();
                        }
                        float disp = (float) Math.hypot(own.locX() - ownFirstX, own.locY() - ownFirstY);
                        if (disp > ownMaxDisp) {
                            ownMaxDisp = disp;
                        }
                        float speed = (float) Math.hypot(own.velX(), own.velY());
                        if (speed > ownMaxSpeed) {
                            ownMaxSpeed = speed;
                        }
                    }
                    if (own != null && foe != null) {
                        float bearing = (float) Math.toDegrees(
                                Math.atan2(foe.locY() - own.locY(), foe.locX() - own.locX()));
                        aimDeg = bearing < 0f ? bearing + 360f : bearing;
                    } else {
                        // Foe absent = the predict death-teardown frame; the server still reads one
                        // intent back for this exchange, so answer with the last aim and keep going
                        // (the next read sees the server's clean socket close -> EOF -> break).
                        aimDeg = Float.isNaN(lastAimDeg) ? 0f : lastAimDeg;
                    }
                } else {
                    PilotWorldViewFrame view = PilotWorldViewWireCodec.readFrame(in);
                    if (view == null || view.terminal()) {
                        break; // clean end of stream / server signalled done
                    }
                    frameSeq = view.frameSeq();
                    aimDeg = view.bearingToTargetDeg();
                }
                // MOVE_* wire encoding, NOT PilotManeuverIntent bits: the live serve loop replays these
                // verbatim via giveCommand, and the legacy bitset's STRAFE_LEFT lands on wire DECELERATE
                // (the zero-displacement strafe regression).
                int heldControls = (frameSeq <= strafeBurst)
                        ? PilotIntentFrame.MOVE_STRAFE_LEFT : 0;
                // gap-⑤ vent lane (opt-in; ventFrom 0 = never): hold the vent bit from frame ventFrom on,
                // giving the server's predictFeed exactly one rising edge to replay via giveCommand(VENT_FLUX).
                if (ventFrom > 0 && frameSeq >= ventFrom) {
                    heldControls |= PilotIntentFrame.VENT_FLUX;
                }
                // system-engage lane (opt-in; systemFrom 0 = never): HOLD bit-7 the whole engaged window so the
                // server's held-driven applySystemBit re-issues useSystem() each cooldown cycle (the LATE
                // re-engagement a live test's residual exposed).
                if (systemFrom > 0 && frameSeq >= systemFrom) {
                    heldControls |= PilotIntentFrame.USE_SHIP_SYSTEM;
                }
                // fireHeld from frame fireFrom on (default 0 = always, the pre-existing behavior). A positive
                // fireFrom gives a no-fire baseline so the measurement can see the flux-rise / boost-drop edge.
                boolean fireHeld = frameSeq >= fireFrom;
                // BasicEngineAI two-client lane: it servos facing onto the target, so ownShipFacingDeg ~= aim;
                // pass aimDeg (don't-care here — only the predict-replay branch consumes it; fix (B)).
                // No own-ship world location is in scope at this construction site (own/foe and view are
                // branch-local above), so this synthetic lane carries no aim world point (0,0 don't-care;
                // its assertions price displacement/speed/byte-identity, not shot landing).
                PilotIntentFrame intent = PilotIntentFrame.of(
                        shipId, frameSeq, aimDeg, heldControls, fireHeld, selectedGroup, aimDeg, 0f, 0f);
                // Serialize once, digest exactly those bytes, then write them — so sentSha256 is a
                // digest of precisely what crossed the wire (the server re-serializes the decoded
                // frames and compares, proving cross-process byte-identity end to end).
                ByteArrayOutputStream frameBytes = new ByteArrayOutputStream(PilotIntentWireCodec.FRAME_BYTES);
                PilotIntentWireCodec.writeFrame(frameBytes, intent);
                byte[] bytes = frameBytes.toByteArray();
                digest.update(bytes);
                out.write(bytes);
                out.flush();
                framesSent++;
                // --decl-every lane: one PDCL declaration rides AFTER the intent within this tick
                // (the server's next read demuxes it FIRST, before that tick's PINT). Bytes intentionally
                // NOT digested — sentSha256 stays a digest of the intent lane alone.
                if (declEvery > 0 && frameSeq % declEvery == 0) {
                    out.write(FireDischargeDeclarationCodec.encodeFrame(
                            // aim world point 0,0 — consistent with this lane's intent frames (no own
                            // location in scope; don't-care for its assertions).
                            FireDischargeDeclaration.of(shipId, declSeq++, frameSeq, aimDeg, 0f, 0f)));
                    out.flush();
                    declSent++;
                }
                lastAimDeg = aimDeg;
            }
        }
        return new Result(framesSent, lastAimDeg, toHex(digest.digest()), gpeeFramesSkipped,
                ownFramesObserved, ownMaxDisp, ownMaxSpeed, declSent);
    }

    /** Latest DOWN state the duplex reader published (one volatile ref = atomic tick+aim pair,
     * the datagram client's AimView posture). {@code null} until the first DOWN frame. */
    private record DuplexView(int simTick, float aimDeg) {
    }

    /** Duplex-mode shared state: reader thread writes, main (writer-clock) thread reads. The
     * movement/receipt trackers are reader-thread-only until the main thread {@code join()}s. */
    private static final class DuplexDownState {
        volatile DuplexView view;
        volatile boolean downEnded;
        volatile IOException readerFailure;
        int gpeeFramesSkipped;
        int ownFramesObserved;
        float ownFirstX = Float.NaN;
        float ownFirstY = Float.NaN;
        float ownMaxDisp;
        float ownMaxSpeed;
    }

    /**
     * The {@code --intent-hz} duplex loop (class doc): reader thread drains DOWN, main thread
     * clocks intents UP. Intent construction (strafe/vent/system/fire lanes, MOVE_* wire bits,
     * digest-then-write) mirrors the ping-pong loop; frameSeq echoes the LATEST-SEEN simTick.
     * The clock STARTS only once the first DOWN frame arrives: the retired serve() pre-loop (git
     * history) blocked in {@code awaitSeatName} BEFORE its first DOWN write and failed CLOSED on
     * any non-SNAM magic —
     * so the only pre-DOWN UP bytes allowed are a real SNAM ({@code --announce-name}, which ends
     * the window early) or none at all (window elapses empty, and with the event lane ON that
     * costs the FULL window — pass an announce when driving an event-lane serve, or the server's
     * silent SNAM wait outlives this client's 60 s read timeout). The one-time
     * first-frame sync is the TCP analogue of the datagram client's WELCOME gate — every frame
     * after it stays clock-driven, no per-frame RTT serialization (the D2 fairness pin); the
     * server's first DOWN lands well inside its join grace. PDCL cadence is frames-SENT based like
     * the datagram client (frameSeq repeats between server ticks here, so the ping-pong
     * frameSeq-modulo would mis-fire), keeping decl_sent == floor(frames_sent / declEvery)
     * deterministic.
     */
    private static Result streamDuplex(
            String host, int port, long shipId, int selectedGroup, int strafeBurst, int ventFrom, int fireFrom,
            int systemFrom, int declEvery, int maxFrames, int intentHz, String announceName)
            throws IOException, InterruptedException {
        MessageDigest digest = newSha256();
        int framesSent = 0;
        int declSent = 0;
        int declSeq = 0;
        float lastAimDeg = Float.NaN;
        DuplexDownState state = new DuplexDownState();
        Thread reader = null;
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            if (!announceName.isEmpty()) {
                // The ONE SNAM announce, before any other UP byte — the rendered-client
                // posture; the retired serve() lane's event-lane awaitSeatName consumed it and
                // moved on instead of blocking out its whole window on a silent client.
                SeatNameFrameCodec.writeAnnounce(out, shipId, announceName);
                out.flush();
            }
            reader = new Thread(() -> duplexReadLoop(in, shipId, state), "pilot-socket-client-reader");
            reader.setDaemon(true);
            reader.start();
            // First-frame gate (method doc): the retired serve() lane's SNAM window had to elapse
            // EMPTY before any UP byte, so wait for the reader's first published DOWN frame
            // before starting the clock (view stays non-null from here on; the null branches
            // below are defensive).
            while (state.view == null && !state.downEnded && state.readerFailure == null) {
                Thread.sleep(1L);
            }
            long periodNanos = 1_000_000_000L / intentHz;
            long nextAtNanos = System.nanoTime();
            while (framesSent < maxFrames && !state.downEnded && state.readerFailure == null) {
                DuplexView view = state.view;
                int frameSeq = view == null ? 0 : view.simTick();
                float aimDeg = view == null ? 0f : view.aimDeg();
                // strafeBurst guarded > 0 like the datagram client.
                int heldControls = (frameSeq <= strafeBurst && strafeBurst > 0)
                        ? PilotIntentFrame.MOVE_STRAFE_LEFT : 0;
                if (ventFrom > 0 && frameSeq >= ventFrom) {
                    heldControls |= PilotIntentFrame.VENT_FLUX;
                }
                if (systemFrom > 0 && frameSeq >= systemFrom) {
                    heldControls |= PilotIntentFrame.USE_SHIP_SYSTEM;
                }
                boolean fireHeld = frameSeq >= fireFrom;
                PilotIntentFrame intent = PilotIntentFrame.of(
                        shipId, frameSeq, aimDeg, heldControls, fireHeld, selectedGroup, aimDeg, 0f, 0f);
                ByteArrayOutputStream frameBytes = new ByteArrayOutputStream(PilotIntentWireCodec.FRAME_BYTES);
                PilotIntentWireCodec.writeFrame(frameBytes, intent);
                byte[] bytes = frameBytes.toByteArray();
                digest.update(bytes);
                try {
                    out.write(bytes);
                    out.flush();
                    framesSent++;
                    if (declEvery > 0 && framesSent % declEvery == 0) {
                        out.write(FireDischargeDeclarationCodec.encodeFrame(
                                FireDischargeDeclaration.of(shipId, declSeq++, frameSeq, aimDeg, 0f, 0f)));
                        out.flush();
                        declSent++;
                    }
                } catch (IOException writeFailed) {
                    if (state.downEnded) {
                        break; // server cap-end close raced our write — the clean duplex ending
                    }
                    throw writeFailed;
                }
                lastAimDeg = aimDeg;
                nextAtNanos += periodNanos;
                long sleepNanos = nextAtNanos - System.nanoTime();
                if (sleepNanos > 0L) {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } else {
                    nextAtNanos = System.nanoTime(); // fell behind (GC/stall) — rebase, don't burst
                }
            }
            IOException failure = state.readerFailure;
            if (failure != null) {
                throw failure; // 60 s DOWN silence / reset while we were still streaming = run failure
            }
        }
        // Socket now closed (unblocks a reader still parked in read); join for visibility of the
        // reader-thread-only trackers before snapshotting them into the Result.
        if (reader != null) {
            reader.join(5_000L);
        }
        return new Result(framesSent, lastAimDeg, toHex(digest.digest()), state.gpeeFramesSkipped,
                state.ownFramesObserved, state.ownMaxDisp, state.ownMaxSpeed, declSent);
    }

    /** Duplex DOWN drain: the SAME per-frame magic dispatch as the ping-pong loop (GPEE
     * decode-and-discard, AWSS own/foe aim, worldview fallback), publishing instead of answering.
     * Clean EOF/terminal sets {@code downEnded}; an IOException while the writer is live (60 s
     * read timeout, reset) is a terminal failure. A "socket closed" exception AFTER the main
     * thread finished is the benign teardown race — main has already stopped reading the flag. */
    private static void duplexReadLoop(BufferedInputStream in, long shipId, DuplexDownState state) {
        try {
            while (true) {
                int magic = peekMagic(in);
                if (magic < 0) {
                    break; // clean end of stream
                }
                if (magic == GameplayEdgeEventCodec.MAGIC) {
                    GameplayEdgeEventCodec.readEvent(in);
                    state.gpeeFramesSkipped++;
                    continue;
                }
                if (magic == SeatNameFrameCodec.SEAT_NAMES_MAGIC) {
                    // The ONE pre-round SNMS an event-lane serve emits after its SNAM window
                    // — decode-and-discard, same posture as the GPEE branch.
                    SeatNameFrameCodec.readSeatNames(in);
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
                    AuthoritativeWorldSnapshot snap = decoded.get(0);
                    AuthoritativeEntitySnapshot own = null;
                    AuthoritativeEntitySnapshot foe = null;
                    for (AuthoritativeEntitySnapshot entity : snap.entities()) {
                        if (entity.entityId() == shipId) {
                            own = entity;
                        } else if (foe == null) {
                            foe = entity;
                        }
                    }
                    if (own != null) {
                        state.ownFramesObserved++;
                        if (Float.isNaN(state.ownFirstX)) {
                            state.ownFirstX = own.locX();
                            state.ownFirstY = own.locY();
                        }
                        float disp = (float) Math.hypot(own.locX() - state.ownFirstX,
                                own.locY() - state.ownFirstY);
                        if (disp > state.ownMaxDisp) {
                            state.ownMaxDisp = disp;
                        }
                        float speed = (float) Math.hypot(own.velX(), own.velY());
                        if (speed > state.ownMaxSpeed) {
                            state.ownMaxSpeed = speed;
                        }
                    }
                    float aimDeg;
                    if (own != null && foe != null) {
                        float bearing = (float) Math.toDegrees(
                                Math.atan2(foe.locY() - own.locY(), foe.locX() - own.locX()));
                        aimDeg = bearing < 0f ? bearing + 360f : bearing;
                    } else {
                        // Foe absent = the death-teardown frame: hold the last aim (ping-pong posture).
                        DuplexView previous = state.view;
                        aimDeg = previous == null ? 0f : previous.aimDeg();
                    }
                    state.view = new DuplexView((int) snap.simTick(), aimDeg);
                    continue;
                }
                PilotWorldViewFrame view = PilotWorldViewWireCodec.readFrame(in);
                if (view == null || view.terminal()) {
                    break;
                }
                state.view = new DuplexView(view.frameSeq(), view.bearingToTargetDeg());
            }
            state.downEnded = true;
        } catch (IOException e) {
            state.readerFailure = new IOException("duplex DOWN reader failed", e);
        }
    }

    /**
     * Peek the next frame's 4-byte magic without consuming it ({@code mark}/{@code reset} on the
     * buffered stream), or -1 at end-of-stream. Lets the per-tick loop dispatch between the puppet
     * {@link PilotWorldViewWireCodec} down-frame and the predict-feed AWSS down-frame.
     */
    private static int peekMagic(BufferedInputStream in) throws IOException {
        in.mark(4);
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        in.reset();
        if ((b0 | b1 | b2 | b3) < 0) {
            return -1; // EOF before a full magic
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
            Path receiptPath, String host, int port, long shipId,
            boolean connected, Result result, String error) throws IOException {
        Path parent = receiptPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        lines.add("client_class=" + PilotIntentSocketClient.class.getName());
        lines.add("host=" + (host == null ? "" : host));
        lines.add("port=" + port);
        lines.add("source_ship_id=" + shipId);
        lines.add("connected=" + connected);
        lines.add("frames_sent=" + result.framesSent());
        lines.add("last_aim_deg=" + result.lastAimDeg());
        lines.add("sent_sha256=" + result.sentSha256());
        lines.add("gpee_frames_skipped=" + result.gpeeFramesSkipped());
        lines.add("own_frames_observed=" + result.ownFramesObserved());
        lines.add("own_max_disp=" + result.ownMaxDisp());
        lines.add("own_max_speed=" + result.ownMaxSpeed());
        lines.add("decl_sent=" + result.declSent());
        lines.add("error=" + error);
        Files.write(receiptPath, lines,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
