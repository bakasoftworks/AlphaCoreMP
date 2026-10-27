package cc.starsector.server.client;

import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.NonShipEntitySnapshot;
import cc.starsector.server.protocol.ShipFluxSnapshot;
import cc.starsector.server.protocol.ShipWeaponMountSnapshot;
import cc.starsector.server.runtime.AuthoritativeWorldSnapshotCodec;
import cc.starsector.server.runtime.LiveClientIntent;
import cc.starsector.server.runtime.LiveClientIntentCodec;
import cc.starsector.server.runtime.LiveCommandAck;
import cc.starsector.server.runtime.LiveCommandAckCodec;
import cc.starsector.server.runtime.LiveManeuverIntent;
import cc.starsector.server.runtime.LiveManeuverIntentCodec;
import cc.starsector.server.runtime.MatchReentryFrameCodec;
import cc.starsector.server.runtime.MatchReentrySignal;
import cc.starsector.server.runtime.MatchResetFrameCodec;
import cc.starsector.server.runtime.MatchResetSignal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Separate-JVM client for the per-entity world-snapshot wire crossing.
 * It is the receiving half of
 * {@link cc.starsector.server.runtime.AuthoritativeWorldSnapshotSocketTransport}:
 * a process with no shared memory connects to the server socket, drains every
 * byte the server streams, decodes them with the real product
 * {@link AuthoritativeWorldSnapshotCodec}, and re-derives the
 * {@code state_changes} property (distinct per-entity state across ticks)
 * independently of the sender.
 *
 * <p>Unlike {@link HeadlessSnapshotSocketClient}, this client decodes the
 * variable-length per-entity {@code AWSS} payload rather than the fixed-width
 * aggregate offline-step frame, and carries no admission handshake: the crossing
 * proves the per-entity codec survives a real socket to a real second process and
 * that the receiver can reconstruct each entity's authoritative state (never
 * re-simulating combat).
 *
 * <p>Fail-closed: any byte-count, frame-count, or decode mismatch exits non-zero
 * with a diagnostic on stderr, and no partial receipt is written.
 */
public final class WorldSnapshotSocketClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;

    private WorldSnapshotSocketClient() {
    }

    /**
     * The capability a concurrent fan-out peer advertises to the server before draining
     * the stream: its index in the fan-out and the maximum
     * world-snapshot schema version it can decode. The server reads exactly this handshake
     * (two big-endian ints) and negotiates which schema to serve this peer.
     */
    public record SchemaAdvertisement(int peerId, int maxSupportedSchemaVersion) {
        public SchemaAdvertisement {
            if (maxSupportedSchemaVersion <= 0) {
                throw new IllegalArgumentException(
                        "maxSupportedSchemaVersion must be > 0; got " + maxSupportedSchemaVersion);
            }
        }
    }

    /** Result of one receive: written to the receipt and returned for in-process tests. */
    public record Receipt(
            String host,
            int port,
            long bytesReceived,
            int framesReceived,
            int entityCountFirstFrame,
            int distinctEntityStates,
            boolean stateChanges,
            String receivedSha256,
            int nonShipRecordsReceived,
            int framesWithNonShip,
            int maxNonShipPerFrame,
            int distinctNonShipStates,
            String firstNonShipDescriptor,
            int fluxRecordsReceived,
            int framesWithFlux,
            int distinctFluxStates,
            boolean fluxStateChanges,
            int weaponMountRecordsReceived,
            int framesWithWeaponMounts,
            int distinctWeaponMountStates,
            boolean weaponMountStateChanges,
            // The typed server->client command acknowledgement this client
            // consumed before draining snapshots (only when --expect-command-ack and a
            // fire intent were sent; otherwise commandAckReceived=false and the rest are
            // "no ack" sentinels). This is the explicit confirmation the FIRE round-trip
            // previously inferred only from snapshot side-effects.
            boolean commandAckReceived,
            long commandAckShipId,
            int commandAckWeaponGroup,
            boolean commandAckFireRequested,
            boolean commandAckAccepted) {
    }

    public static void main(String[] args) {
        try {
            String host = null;
            int port = -1;
            Path artifactPath = null;
            Path receiptPath = null;
            int expectedFrames = -1;
            // When --send-intent-fire is present the client SENDS one
            // LiveClientIntent over the socket before draining snapshots, closing the
            // authoritative round-trip (its own FIRE/HOLD command threads into the live step).
            Boolean intentFire = null;
            long intentShipId = 1L;
            int intentWeaponGroup = 0;
            // The maneuver sibling — when --send-maneuver is present the client
            // SENDS one LiveManeuverIntent over the socket before draining snapshots, closing
            // the authoritative round-trip on a movement command (its own ACCELERATE/TURN
            // intent threads into the live step and the motion effect crosses back).
            Boolean maneuverActive = null;
            long maneuverShipId = 1L;
            int maneuverMovementOrdinal = 0;
            // When --advertise-max-schema is present the client first ADVERTISES
            // its (peerId, maxSupportedSchema) over the socket so the server can negotiate
            // which world-snapshot schema to serve THIS peer in a concurrent fan-out.
            int advertisePeerId = 0;
            Integer advertiseMaxSchema = null;
            // When --expect-command-ack true is present (and a fire intent is
            // sent), the client READS one typed LiveCommandAck off the socket before it
            // drains snapshots, closing the explicit command-result feedback loop.
            boolean expectCommandAck = false;
            // When --continuous true, the client drives the live per-frame intent
            // channel — it SENDS one LiveManeuverIntent per frame (the per-frame ordinals in
            // --maneuver-script, repeating the last once exhausted) over a single connection and
            // reads the resulting frames in lockstep, so a mid-session command change lands
            // without a reconnect. Uses --send-maneuver-ship for the commanded ship.
            boolean continuous = false;
            int[] maneuverScript = null;
            // When --rounds-per-socket > 1 the continuous client speaks the
            // round-boundary protocol (MatchAwareSnapshotStream MRST down / MatchReentryFrameCodec
            // MRER up) so ONE socket carries multiple lethal rounds with a server reset between
            // them. Default 1 = the single-round per-frame channel, unchanged.
            int roundsPerSocket = 1;
            for (int i = 0; i + 1 < args.length; i += 2) {
                switch (args[i]) {
                    case "--host" -> host = args[i + 1];
                    case "--port" -> port = Integer.parseInt(args[i + 1]);
                    case "--artifact" -> artifactPath = Path.of(args[i + 1]);
                    case "--receipt" -> receiptPath = Path.of(args[i + 1]);
                    case "--expected-frames" -> expectedFrames = Integer.parseInt(args[i + 1]);
                    case "--send-intent-fire" -> intentFire = Boolean.parseBoolean(args[i + 1]);
                    case "--send-intent-ship" -> intentShipId = Long.parseLong(args[i + 1]);
                    case "--send-intent-group" -> intentWeaponGroup = Integer.parseInt(args[i + 1]);
                    case "--send-maneuver" -> maneuverActive = Boolean.parseBoolean(args[i + 1]);
                    case "--send-maneuver-ship" -> maneuverShipId = Long.parseLong(args[i + 1]);
                    case "--send-maneuver-movement" -> maneuverMovementOrdinal = Integer.parseInt(args[i + 1]);
                    case "--advertise-peer-id" -> advertisePeerId = Integer.parseInt(args[i + 1]);
                    case "--advertise-max-schema" -> advertiseMaxSchema = Integer.parseInt(args[i + 1]);
                    case "--expect-command-ack" -> expectCommandAck = Boolean.parseBoolean(args[i + 1]);
                    case "--continuous" -> continuous = Boolean.parseBoolean(args[i + 1]);
                    case "--maneuver-script" -> maneuverScript = parseOrdinals(args[i + 1]);
                    case "--rounds-per-socket" -> roundsPerSocket = Integer.parseInt(args[i + 1]);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (host == null || port < 0 || artifactPath == null || receiptPath == null) {
                throw new IllegalArgumentException(
                        "required: --host --port --artifact --receipt [--expected-frames]"
                                + " [--send-intent-fire <bool> --send-intent-ship <id> --send-intent-group <g>]"
                                + " [--send-maneuver <bool> --send-maneuver-ship <id> --send-maneuver-movement <ordinal>]"
                                + " [--advertise-peer-id <id> --advertise-max-schema <v>]");
            }
            LiveClientIntent intent = intentFire == null
                    ? null
                    : new LiveClientIntent(intentShipId, intentFire, intentWeaponGroup);
            LiveManeuverIntent maneuverIntent = maneuverActive == null
                    ? null
                    : new LiveManeuverIntent(maneuverShipId, maneuverActive, maneuverMovementOrdinal);
            if (intent != null && maneuverIntent != null) {
                throw new IllegalArgumentException(
                        "send either a fire intent or a maneuver intent, not both");
            }
            SchemaAdvertisement advertisement = advertiseMaxSchema == null
                    ? null
                    : new SchemaAdvertisement(advertisePeerId, advertiseMaxSchema);

            if (continuous) {
                // Live per-frame intent channel: stream a fresh maneuver intent per frame over a
                // single connection (mid-session command change without reconnect).
                if (advertisement == null) {
                    throw new IllegalArgumentException(
                            "--continuous requires --advertise-max-schema (the per-peer handshake)");
                }
                if (maneuverScript == null) {
                    throw new IllegalArgumentException(
                            "--continuous requires --maneuver-script <csv-of-movement-ordinals>");
                }
                if (expectedFrames < 0) {
                    throw new IllegalArgumentException("--continuous requires --expected-frames");
                }
                if (roundsPerSocket > 1) {
                    // Round-aware multi-round channel: --expected-frames is the
                    // per-round frame cap (== the server's --frames-per-session), and the client speaks
                    // the MRST/MRER round-boundary handshake between rounds over the one socket.
                    Receipt roundsReceipt = receiveContinuousManeuverRounds(
                            host, port, artifactPath, roundsPerSocket, expectedFrames, advertisement,
                            maneuverShipId, maneuverScript);
                    writeReceipt(receiptPath, roundsReceipt);
                    System.out.println("world-snapshot continuous-rounds client OK: rounds="
                            + roundsPerSocket
                            + " frames=" + roundsReceipt.framesReceived()
                            + " bytes=" + roundsReceipt.bytesReceived()
                            + " distinct_entity_states=" + roundsReceipt.distinctEntityStates()
                            + " state_changes=" + roundsReceipt.stateChanges()
                            + " ship=" + maneuverShipId);
                    System.exit(0);
                }
                Receipt continuousReceipt = receiveContinuousManeuver(
                        host, port, artifactPath, expectedFrames, advertisement, maneuverShipId, maneuverScript);
                writeReceipt(receiptPath, continuousReceipt);
                System.out.println("world-snapshot continuous client OK: frames="
                        + continuousReceipt.framesReceived()
                        + " bytes=" + continuousReceipt.bytesReceived()
                        + " distinct_entity_states=" + continuousReceipt.distinctEntityStates()
                        + " state_changes=" + continuousReceipt.stateChanges()
                        + " intents_sent=" + expectedFrames
                        + " ship=" + maneuverShipId);
                System.exit(0);
            }

            Receipt receipt = receive(
                    host, port, artifactPath, expectedFrames, intent, advertisement, maneuverIntent,
                    expectCommandAck);
            writeReceipt(receiptPath, receipt);
            System.out.println("world-snapshot socket client OK: frames=" + receipt.framesReceived()
                    + " bytes=" + receipt.bytesReceived()
                    + " distinct_entity_states=" + receipt.distinctEntityStates()
                    + " state_changes=" + receipt.stateChanges()
                    + " non_ship_records=" + receipt.nonShipRecordsReceived()
                    + " distinct_non_ship_states=" + receipt.distinctNonShipStates()
                    + " flux_records=" + receipt.fluxRecordsReceived()
                    + " distinct_flux_states=" + receipt.distinctFluxStates()
                    + " weapon_mount_records=" + receipt.weaponMountRecordsReceived()
                    + " distinct_weapon_mount_states=" + receipt.distinctWeaponMountStates()
                    + " command_ack_received=" + receipt.commandAckReceived()
                    + " command_ack_accepted=" + receipt.commandAckAccepted());
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Connect, drain all bytes to {@code artifactPath}, decode them with the real
     * codec, and re-derive the per-entity state-change property.
     */
    public static Receipt receive(String host, int port, Path artifactPath, int expectedFrames)
            throws IOException {
        return receive(host, port, artifactPath, expectedFrames, null);
    }

    /**
     * Connect, optionally <em>send</em> one {@link LiveClientIntent} over the socket
     * (closing the authoritative round-trip), then drain all
     * snapshot bytes the server streams back and decode them.
     */
    public static Receipt receive(
            String host, int port, Path artifactPath, int expectedFrames, LiveClientIntent intent)
            throws IOException {
        return receive(host, port, artifactPath, expectedFrames, intent, null);
    }

    /**
     * Connect, optionally advertise this peer's decode capability ({@code advertisement})
     * and/or <em>send</em> one {@link LiveClientIntent},
     * then drain all snapshot bytes the server streams back and decode
     * them. The advertisement, when present, is written first so the server reads it before
     * it negotiates this peer's served schema.
     */
    public static Receipt receive(
            String host,
            int port,
            Path artifactPath,
            int expectedFrames,
            LiveClientIntent intent,
            SchemaAdvertisement advertisement)
            throws IOException {
        return receive(host, port, artifactPath, expectedFrames, intent, advertisement, null);
    }

    /**
     * Full variant: optionally advertise, optionally <em>send</em> one
     * {@link LiveClientIntent} (FIRE) <em>or</em> one
     * {@link LiveManeuverIntent} (movement) over the socket, then drain
     * and decode the snapshot bytes the server streams back. A given client window carries
     * either a fire intent or a maneuver intent, never both.
     */
    public static Receipt receive(
            String host,
            int port,
            Path artifactPath,
            int expectedFrames,
            LiveClientIntent intent,
            SchemaAdvertisement advertisement,
            LiveManeuverIntent maneuverIntent)
            throws IOException {
        return receive(
                host, port, artifactPath, expectedFrames, intent, advertisement, maneuverIntent, false);
    }

    /**
     * Fullest variant: in addition to the optional advertisement /
     * fire intent / maneuver intent, when {@code expectCommandAck} is set the client
     * READS one typed {@link LiveCommandAck} off the socket immediately after sending its
     * fire intent and before draining snapshots — the explicit server-authored
     * confirmation of the command, decoded with {@link LiveCommandAckCodec}. Requires a
     * fire intent: the ack acknowledges that command. Fail-closed: a missing/garbled ack,
     * or an ack that does not match the sent intent, throws and writes no receipt.
     */
    public static Receipt receive(
            String host,
            int port,
            Path artifactPath,
            int expectedFrames,
            LiveClientIntent intent,
            SchemaAdvertisement advertisement,
            LiveManeuverIntent maneuverIntent,
            boolean expectCommandAck)
            throws IOException {
        if (intent != null && maneuverIntent != null) {
            throw new IllegalArgumentException(
                    "send either a fire intent or a maneuver intent, not both");
        }
        if (expectCommandAck && intent == null) {
            throw new IllegalArgumentException(
                    "expectCommandAck requires a fire intent to acknowledge");
        }
        Path parent = artifactPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] received;
        LiveCommandAck commandAck = null;
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            if (advertisement != null) {
                // Advertise (peerId, maxSchema) first; the server reads exactly these two
                // ints and negotiates which schema to serve this peer before streaming.
                DataOutputStream advertOut = new DataOutputStream(socket.getOutputStream());
                advertOut.writeInt(advertisement.peerId());
                advertOut.writeInt(advertisement.maxSupportedSchemaVersion());
                advertOut.flush();
            }
            if (intent != null) {
                // Send the intent first; the server reads exactly this frame before it
                // begins publishing, so the snapshots that follow reflect this command.
                LiveClientIntentCodec.writeIntent(socket.getOutputStream(), intent);
            }
            if (maneuverIntent != null) {
                // The maneuver sibling — send the movement intent first so the
                // snapshots that follow reflect the commanded ship's motion under it.
                LiveManeuverIntentCodec.writeIntent(socket.getOutputStream(), maneuverIntent);
            }
            var socketIn = socket.getInputStream();
            if (expectCommandAck) {
                // Read the typed server ack FIRST (one fixed-width frame), then
                // drain the snapshot stream. The server writes this ack after reading our
                // intent and before it begins publishing, so it precedes the AWSS frames on
                // the wire. Decoding it here makes the registered server.ship_fire.result
                // message a real wire message with a live consumer.
                commandAck = LiveCommandAckCodec.readAck(socketIn);
                if (commandAck.shipId() != intent.shipId()
                        || commandAck.weaponGroup() != intent.weaponGroup()
                        || commandAck.fireRequested() != intent.fire()) {
                    throw new IOException("command ack does not match the sent intent: ack="
                            + commandAck + " intent=" + intent);
                }
            }
            received = socketIn.readAllBytes();
        }
        Files.write(artifactPath, received,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        List<AuthoritativeWorldSnapshot> snapshots =
                AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(received));
        if (expectedFrames >= 0 && snapshots.size() != expectedFrames) {
            throw new IOException("world snapshot socket client frame count mismatch: expected "
                    + expectedFrames + ", got " + snapshots.size());
        }
        return analyze(host, port, received, snapshots, commandAck);
    }

    /**
     * Live per-frame intent channel: connect, advertise this peer, then
     * drive a SINGLE connection in lockstep — for each of {@code expectedFrames} frames, SEND
     * this frame's {@link LiveManeuverIntent} (the movement from {@code movementScript},
     * repeating its last entry once exhausted) for {@code maneuverShipId} and then RECEIVE
     * exactly one resulting governed frame. Because the command sent can change every frame
     * over the one connection, a mid-session command switch lands on the next frame WITHOUT a
     * reconnect — the fix for the single-held-intent stutter an operator watched.
     * Returns the same {@link Receipt} the EOF-draining {@link #receive}
     * does. Fail-closed: a short read, an early server EOF, or a frame-count mismatch throws and
     * writes no receipt.
     */
    public static Receipt receiveContinuousManeuver(
            String host,
            int port,
            Path artifactPath,
            int expectedFrames,
            SchemaAdvertisement advertisement,
            long maneuverShipId,
            int[] movementScript) throws IOException {
        if (advertisement == null) {
            throw new IllegalArgumentException(
                    "the continuous per-frame intent channel requires a schema advertisement");
        }
        if (expectedFrames < 1) {
            throw new IllegalArgumentException(
                    "expectedFrames must be >= 1 for the continuous channel; got " + expectedFrames);
        }
        if (movementScript == null || movementScript.length < 1) {
            throw new IllegalArgumentException("movementScript must carry at least one movement ordinal");
        }
        Path parent = artifactPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            // Advertise (peerId, maxSchema) first; the server reads exactly these two ints and
            // negotiates this peer's served schema before the per-frame exchange begins.
            DataOutputStream advertOut = new DataOutputStream(out);
            advertOut.writeInt(advertisement.peerId());
            advertOut.writeInt(advertisement.maxSupportedSchemaVersion());
            advertOut.flush();
            var socketIn = socket.getInputStream();
            for (int frame = 0; frame < expectedFrames; frame++) {
                int ordinal = movementScript[Math.min(frame, movementScript.length - 1)];
                LiveManeuverIntent intent = LiveManeuverIntent.maneuver(
                        maneuverShipId, ShipCommandSink.Movement.values()[ordinal]);
                // Lockstep: SEND this frame's intent, then RECEIVE exactly one frame. The server
                // reads the intent, issues that command, integrates, and streams the resulting
                // frame back; the next iteration may send a DIFFERENT intent (mid-session change).
                LiveManeuverIntentCodec.writeIntent(out, intent);
                byte[] frameBytes = AuthoritativeWorldSnapshotCodec.readFrameBytes(socketIn);
                if (frameBytes == null) {
                    throw new IOException("continuous client: server closed the stream after "
                            + frame + " of " + expectedFrames + " frames");
                }
                received.write(frameBytes);
            }
        }
        byte[] receivedBytes = received.toByteArray();
        Files.write(artifactPath, receivedBytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        List<AuthoritativeWorldSnapshot> snapshots =
                AuthoritativeWorldSnapshotCodec.readSnapshots(new ByteArrayInputStream(receivedBytes));
        if (snapshots.size() != expectedFrames) {
            throw new IOException("continuous client frame count mismatch: expected "
                    + expectedFrames + ", got " + snapshots.size());
        }
        return analyze(host, port, receivedBytes, snapshots, null);
    }

    /**
     * Round-aware multi-round continuous channel: the round-boundary speaker the
     * persistent-socket multi-round server ({@code serveContinuousCommandRoundsOnOneClient}) needs. Like
     * {@link #receiveContinuousManeuver} it drives a SINGLE connection in lockstep — SEND this frame's
     * {@link LiveManeuverIntent} for {@code maneuverShipId}, RECEIVE exactly one governed frame — but it
     * carries {@code roundsPerSocket} lethal rounds over the one socket, speaking the round-boundary protocol
     * between them:
     *
     * <ul>
     *   <li><b>End-of-round (down).</b> The server's per-side round-end trigger
     *       ({@code cc.server.perSideRoundEnd}) flips {@code matchOver} when one fighting side is eliminated;
     *       in stable-roster mode that side's dead ship is dropped, so the roster SHRINKS below the round's
     *       first-frame entity count on the final frame (a 1v1 ends 2&rarr;1; the symmetric mutual-kill ends
     *       2&rarr;0 — both are {@code < initial}). {@code matchOver} never touches the snapshot wire bytes
     *       (byte-identity), so this entity-count shrink is the in-band end-of-round signal. The client STOPS
     *       sending the instant it sees the shrink — a stray intent would precede the re-entry frame and break
     *       the boundary phase-change framing.</li>
     *   <li><b>Re-entry (up).</b> After the final frame of a non-terminal round the server emits exactly one
     *       {@code MRST} {@link MatchResetSignal} ({@code -Dcc.server.emitMatchResetFrame}) DOWN and then
     *       blocks reading our {@code MRER} {@link MatchReentrySignal} UP. The client reads + validates the
     *       MRST, then sends ONE MRER to release the next round on the SAME socket.</li>
     * </ul>
     *
     * <p>Requires the server run with {@code -Dcc.server.roundsPerSocket}={@code roundsPerSocket},
     * {@code -Dcc.server.emitMatchResetFrame=true}, {@code -Dcc.server.awaitReentry=true} and (for the
     * asymmetric piloted lane) {@code -Dcc.server.perSideRoundEnd=true}. {@code framesPerRoundCap} must equal
     * the server's {@code --frames-per-session}: a round that reaches the cap WITHOUT a one-side elimination
     * never produces a {@code matchOver}, so the server will not reset — the client reports that as a
     * fail-closed {@link IOException} rather than hanging. Fail-closed throughout: a non-AWSS frame mid-round,
     * a missing/mismatched MRST at a boundary, or a trailing byte after the final round throws.
     */
    public static Receipt receiveContinuousManeuverRounds(
            String host,
            int port,
            Path artifactPath,
            int roundsPerSocket,
            int framesPerRoundCap,
            SchemaAdvertisement advertisement,
            long maneuverShipId,
            int[] movementScript) throws IOException {
        if (advertisement == null) {
            throw new IllegalArgumentException(
                    "the continuous round-aware channel requires a schema advertisement");
        }
        if (roundsPerSocket < 2) {
            throw new IllegalArgumentException(
                    "roundsPerSocket must be >= 2 for the round-aware channel; got " + roundsPerSocket);
        }
        if (framesPerRoundCap < 1) {
            throw new IllegalArgumentException("framesPerRoundCap must be >= 1; got " + framesPerRoundCap);
        }
        if (movementScript == null || movementScript.length < 1) {
            throw new IllegalArgumentException("movementScript must carry at least one movement ordinal");
        }
        Path parent = artifactPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        List<AuthoritativeWorldSnapshot> snapshots = new ArrayList<>();
        int[] framesPerRound = new int[roundsPerSocket];
        int resetsSeen = 0;
        int globalFrame = 0;
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            DataOutputStream advertOut = new DataOutputStream(out);
            advertOut.writeInt(advertisement.peerId());
            advertOut.writeInt(advertisement.maxSupportedSchemaVersion());
            advertOut.flush();
            // Byte-faithful capture: a tee mirrors every consumed byte into `received`, so the artifact is the
            // exact interleaved AWSS+MRST wire stream (re-decodable with MatchAwareSnapshotStream).
            DataInputStream in = new DataInputStream(teeInto(socket.getInputStream(), received));

            for (int round = 0; round < roundsPerSocket; round++) {
                int roundInitialEntityCount = -1;
                boolean roundEnded = false;
                for (int frameInRound = 0; frameInRound < framesPerRoundCap; frameInRound++) {
                    int ordinal = movementScript[Math.min(globalFrame, movementScript.length - 1)];
                    LiveManeuverIntent intent = LiveManeuverIntent.maneuver(
                            maneuverShipId, ShipCommandSink.Movement.values()[ordinal]);
                    LiveManeuverIntentCodec.writeIntent(out, intent);
                    out.flush();
                    globalFrame++;
                    // Within a round the next DOWN frame is always a snapshot (the server emits MRST only at a
                    // round boundary, after we stop sending), so a non-AWSS magic here is a protocol error.
                    int magic = in.readInt();
                    if (magic != AuthoritativeWorldSnapshotCodec.MAGIC) {
                        throw new IOException("round " + round + " frame " + frameInRound
                                + ": expected an AWSS snapshot frame, got magic 0x"
                                + Integer.toHexString(magic));
                    }
                    AuthoritativeWorldSnapshot snap = AuthoritativeWorldSnapshotCodec.readFrameAfterMagic(in);
                    snapshots.add(snap);
                    framesPerRound[round]++;
                    if (roundInitialEntityCount < 0) {
                        roundInitialEntityCount = snap.entityCount();
                    }
                    if (snap.entityCount() < roundInitialEntityCount) {
                        // One side eliminated (roster shrank): this is the round's final frame. STOP without
                        // sending another intent.
                        roundEnded = true;
                        break;
                    }
                }
                if (!roundEnded) {
                    throw new IOException("round " + round + " reached the " + framesPerRoundCap
                            + "-frame cap without a one-side elimination (no roster shrink): the round never"
                            + " produced a matchOver, so the server will not reset. Raise --frames or the"
                            + " maneuver is not lethal enough.");
                }
                if (round < roundsPerSocket - 1) {
                    // Round boundary: the server emitted exactly one MRST DOWN after the final frame and now
                    // blocks reading our MRER UP. Read + validate the MRST, then send ONE MRER to release the
                    // next round on the SAME socket (the complement handshake).
                    int magic = in.readInt();
                    if (magic != MatchResetFrameCodec.MAGIC) {
                        throw new IOException("round " + round + " boundary: expected an MRST match-reset"
                                + " frame, got magic 0x" + Integer.toHexString(magic)
                                + " (is -Dcc.server.emitMatchResetFrame set on the server?)");
                    }
                    MatchResetSignal reset = MatchResetFrameCodec.readFrameAfterMagic(in);
                    resetsSeen++;
                    if (reset.completedRoundIndex() != round || reset.nextRoundIndex() != round + 1) {
                        throw new IOException("round " + round + " boundary: MRST round brackets mismatch: "
                                + reset);
                    }
                    MatchReentryFrameCodec.writeFrame(
                            out, new MatchReentrySignal(resetsSeen, round, round + 1));
                    out.flush();
                }
            }
            // After the final round the server half-closes its output; confirm a clean EOF (no extra frames).
            int trailing = in.read();
            if (trailing >= 0) {
                throw new IOException("unexpected trailing byte after the final round: 0x"
                        + Integer.toHexString(trailing));
            }
        }
        byte[] receivedBytes = received.toByteArray();
        Files.write(artifactPath, receivedBytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        if (resetsSeen != roundsPerSocket - 1) {
            throw new IOException("expected " + (roundsPerSocket - 1) + " round-boundary resets, saw "
                    + resetsSeen);
        }
        System.out.println("world-snapshot continuous-rounds frames_per_round="
                + java.util.Arrays.toString(framesPerRound) + " resets=" + resetsSeen);
        return analyze(host, port, receivedBytes, snapshots, null);
    }

    /**
     * Wrap {@code source} so every byte consumed through the returned stream is also mirrored into
     * {@code sink}, giving a byte-faithful capture of exactly what the client reads off the socket (used by
     * the round-aware channel to record the interleaved AWSS+MRST wire stream while decoding it frame by
     * frame). {@link DataInputStream#readInt()} and {@link DataInputStream#readFully(byte[])} bottom out in
     * {@code read()} / {@code read(byte[],int,int)}, both of which this overrides.
     */
    private static InputStream teeInto(InputStream source, ByteArrayOutputStream sink) {
        return new FilterInputStream(source) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    sink.write(b);
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    sink.write(b, off, n);
                }
                return n;
            }
        };
    }

    /**
     * Decode the per-tier state-change properties (entity / non-ship / flux / weapon-mount)
     * from the frames this client drained and fold them, plus the optional typed command ack,
     * into a {@link Receipt}. Shared by the EOF-draining {@link #receive} path and the
     * lockstep {@link #receiveContinuousManeuver} per-frame intent channel
     * so both clients compute the identical receipt the way the server's distinct-state
     * property is computed.
     */
    private static Receipt analyze(
            String host,
            int port,
            byte[] received,
            List<AuthoritativeWorldSnapshot> snapshots,
            LiveCommandAck commandAck) throws IOException {
        Set<String> distinct = new HashSet<>();
        Set<String> distinctNonShip = new HashSet<>();
        Set<String> distinctFlux = new HashSet<>();
        Set<String> distinctWeaponMount = new HashSet<>();
        int nonShipRecordsReceived = 0;
        int framesWithNonShip = 0;
        int maxNonShipPerFrame = 0;
        String firstNonShipDescriptor = "";
        int fluxRecordsReceived = 0;
        int framesWithFlux = 0;
        int weaponMountRecordsReceived = 0;
        int framesWithWeaponMounts = 0;
        for (AuthoritativeWorldSnapshot snapshot : snapshots) {
            distinct.add(entityStateKey(snapshot));
            distinctNonShip.add(nonShipStateKey(snapshot));
            distinctFlux.add(fluxStateKey(snapshot));
            distinctWeaponMount.add(weaponMountStateKey(snapshot));
            int count = snapshot.nonShipEntityCount();
            nonShipRecordsReceived += count;
            if (count > 0) {
                framesWithNonShip++;
                maxNonShipPerFrame = Math.max(maxNonShipPerFrame, count);
                if (firstNonShipDescriptor.isEmpty()) {
                    NonShipEntitySnapshot first = snapshot.nonShipEntities().get(0);
                    firstNonShipDescriptor = first.kind().name()
                            + ":owner=" + first.owner()
                            + ":pos=" + first.posX() + "," + first.posY()
                            + ":contentId=" + first.contentId();
                }
            }
            int fluxCount = snapshot.fluxStateCount();
            fluxRecordsReceived += fluxCount;
            if (fluxCount > 0) {
                framesWithFlux++;
            }
            int weaponMountCount = snapshot.weaponMountCount();
            weaponMountRecordsReceived += weaponMountCount;
            if (weaponMountCount > 0) {
                framesWithWeaponMounts++;
            }
        }
        int entityCountFirst = snapshots.isEmpty() ? 0 : snapshots.get(0).entityCount();
        boolean stateChanges = distinct.size() > 1;
        return new Receipt(
                host,
                port,
                received.length,
                snapshots.size(),
                entityCountFirst,
                distinct.size(),
                stateChanges,
                sha256Hex(received),
                nonShipRecordsReceived,
                framesWithNonShip,
                maxNonShipPerFrame,
                distinctNonShip.size(),
                firstNonShipDescriptor,
                fluxRecordsReceived,
                framesWithFlux,
                distinctFlux.size(),
                distinctFlux.size() > 1,
                weaponMountRecordsReceived,
                framesWithWeaponMounts,
                distinctWeaponMount.size(),
                distinctWeaponMount.size() > 1,
                commandAck != null,
                commandAck == null ? -1L : commandAck.shipId(),
                commandAck == null ? -1 : commandAck.weaponGroup(),
                commandAck != null && commandAck.fireRequested(),
                commandAck != null && commandAck.accepted());
    }

    /**
     * Canonical digest of one frame's per-entity records, EXCLUDING the monotonic
     * {@code simTick}, so the distinct count over frames proves entity
     * motion/durability evolved and not merely the tick counter — matching the
     * real-engine world-snapshot verifier's semantics.
     */
    private static String entityStateKey(AuthoritativeWorldSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(snapshot.entityCount());
        for (AuthoritativeEntitySnapshot entity : snapshot.entities()) {
            data.writeLong(entity.entityId());
            data.writeInt(entity.contentId());
            data.writeLong(entity.spawnTick());
            data.writeInt(entity.ownerConnectionId());
            data.writeInt(Float.floatToIntBits(entity.locX()));
            data.writeInt(Float.floatToIntBits(entity.locY()));
            data.writeInt(Float.floatToIntBits(entity.velX()));
            data.writeInt(Float.floatToIntBits(entity.velY()));
            data.writeInt(Float.floatToIntBits(entity.facing()));
            data.writeInt(Float.floatToIntBits(entity.angularVelocity()));
            data.writeInt(Float.floatToIntBits(entity.hitpoints()));
            data.writeInt(Float.floatToIntBits(entity.hullLevel()));
            data.writeInt(entity.armorCellCount());
            data.writeInt(Float.floatToIntBits(entity.armorTotal()));
            data.writeLong(entity.armorGridHash());
        }
        // Mirror AuthoritativeWorldSnapshotSocketTransport.distinctEntityStateCount:
        // the non-ship tier is folded into the same per-frame key so the server and
        // this separate client compute the identical distinct-state property.
        data.writeInt(snapshot.nonShipEntityCount());
        for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
            data.writeLong(nonShip.networkId());
            data.writeInt(nonShip.kindWireCode());
            data.writeInt(nonShip.owner());
            data.writeInt(Float.floatToIntBits(nonShip.posX()));
            data.writeInt(Float.floatToIntBits(nonShip.posY()));
            data.writeInt(nonShip.contentId());
        }
        // The flux tier (schema v3) is folded into the same per-frame key.
        // For a flux-empty frame (every pre-v3 capture) this appends a constant 0, so
        // the distinct-state cardinality and server<->client agreement are unchanged
        // for existing crossings; a v3 frame whose flux evolves bumps the cardinality.
        appendFluxBytes(data, snapshot);
        // The weapon-mount tier (schema v4) is folded in the same way: a
        // mount-empty frame (every pre-v4 capture) appends a constant 0, so existing
        // crossings are unchanged; a v4 frame whose mounts evolve (ammo draining,
        // cooldown cycling) bumps the cardinality.
        appendWeaponMountBytes(data, snapshot);
        data.flush();
        return sha256Hex(bytes.toByteArray());
    }

    /**
     * Digest of just the non-ship tier of one frame, so the distinct count over
     * frames proves the decoded projectiles/missiles/beams themselves evolved (e.g.
     * a beam impact endpoint moving), independent of ship motion.
     */
    private static String nonShipStateKey(AuthoritativeWorldSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(snapshot.nonShipEntityCount());
        for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
            data.writeLong(nonShip.networkId());
            data.writeInt(nonShip.kindWireCode());
            data.writeInt(nonShip.owner());
            data.writeInt(Float.floatToIntBits(nonShip.posX()));
            data.writeInt(Float.floatToIntBits(nonShip.posY()));
            data.writeInt(nonShip.contentId());
        }
        data.flush();
        return sha256Hex(bytes.toByteArray());
    }

    /**
     * Digest of just the flux tier of one frame, so the distinct count over frames
     * proves the decoded per-ship flux/shield state itself evolved (flux climbing,
     * an overload latching, a shield dropping) independent of ship motion and the
     * non-ship tier — the schema-v3 counterpart of {@link #nonShipStateKey}.
     */
    private static String fluxStateKey(AuthoritativeWorldSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        appendFluxBytes(data, snapshot);
        data.flush();
        return sha256Hex(bytes.toByteArray());
    }

    /** Append the canonical flux-tier bytes (count, then per-record fields) to {@code data}. */
    private static void appendFluxBytes(DataOutputStream data, AuthoritativeWorldSnapshot snapshot)
            throws IOException {
        data.writeInt(snapshot.fluxStateCount());
        for (ShipFluxSnapshot flux : snapshot.fluxStates()) {
            data.writeLong(flux.entityId());
            data.writeInt(Float.floatToIntBits(flux.currFlux()));
            data.writeInt(Float.floatToIntBits(flux.maxFlux()));
            data.writeInt(Float.floatToIntBits(flux.fluxLevel()));
            data.writeInt(Float.floatToIntBits(flux.hardFlux()));
            data.writeInt(flux.statusFlags());
        }
    }

    /**
     * Digest of just the weapon-mount tier of one frame, so the distinct count over
     * frames proves the decoded per-mount readiness itself evolved (ammo draining, a
     * cooldown cycling, a mount disabling) independent of ship motion, the non-ship
     * tier, and the flux tier — the schema-v4 counterpart of {@link #fluxStateKey}.
     */
    private static String weaponMountStateKey(AuthoritativeWorldSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        appendWeaponMountBytes(data, snapshot);
        data.flush();
        return sha256Hex(bytes.toByteArray());
    }

    /** Append the canonical weapon-mount-tier bytes (count, then per-record fields) to {@code data}. */
    private static void appendWeaponMountBytes(DataOutputStream data, AuthoritativeWorldSnapshot snapshot)
            throws IOException {
        data.writeInt(snapshot.weaponMountCount());
        for (ShipWeaponMountSnapshot mount : snapshot.weaponMounts()) {
            data.writeLong(mount.entityId());
            data.writeInt(mount.mountIndex());
            data.writeInt(mount.weaponContentId());
            data.writeInt(Float.floatToIntBits(mount.cooldownRemaining()));
            data.writeInt(Float.floatToIntBits(mount.cooldown()));
            data.writeInt(mount.ammo());
            data.writeInt(mount.maxAmmo());
            data.writeInt(Float.floatToIntBits(mount.chargeLevel()));
            data.writeInt(mount.statusFlags());
        }
    }

    /** Parse a comma-separated list of movement ordinals (the per-frame --maneuver-script). */
    private static int[] parseOrdinals(String csv) {
        String[] parts = csv.split(",");
        int[] ordinals = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ordinals[i] = Integer.parseInt(parts[i].trim());
        }
        return ordinals;
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeReceipt(Path receiptPath, Receipt receipt) throws IOException {
        Path parent = receiptPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        lines.add("client_class=" + WorldSnapshotSocketClient.class.getName());
        lines.add("host=" + receipt.host());
        lines.add("port=" + receipt.port());
        lines.add("bytes_received=" + receipt.bytesReceived());
        lines.add("frames_received=" + receipt.framesReceived());
        lines.add("entity_count_first_frame=" + receipt.entityCountFirstFrame());
        lines.add("distinct_entity_states=" + receipt.distinctEntityStates());
        lines.add("state_changes=" + receipt.stateChanges());
        lines.add("received_sha256=" + receipt.receivedSha256());
        lines.add("non_ship_records_received=" + receipt.nonShipRecordsReceived());
        lines.add("frames_with_non_ship=" + receipt.framesWithNonShip());
        lines.add("max_non_ship_per_frame=" + receipt.maxNonShipPerFrame());
        lines.add("distinct_non_ship_states=" + receipt.distinctNonShipStates());
        lines.add("first_non_ship_descriptor=" + receipt.firstNonShipDescriptor());
        lines.add("flux_records_received=" + receipt.fluxRecordsReceived());
        lines.add("frames_with_flux=" + receipt.framesWithFlux());
        lines.add("distinct_flux_states=" + receipt.distinctFluxStates());
        lines.add("flux_state_changes=" + receipt.fluxStateChanges());
        lines.add("weapon_mount_records_received=" + receipt.weaponMountRecordsReceived());
        lines.add("frames_with_weapon_mounts=" + receipt.framesWithWeaponMounts());
        lines.add("distinct_weapon_mount_states=" + receipt.distinctWeaponMountStates());
        lines.add("weapon_mount_state_changes=" + receipt.weaponMountStateChanges());
        lines.add("command_ack_received=" + receipt.commandAckReceived());
        lines.add("command_ack_ship_id=" + receipt.commandAckShipId());
        lines.add("command_ack_weapon_group=" + receipt.commandAckWeaponGroup());
        lines.add("command_ack_fire_requested=" + receipt.commandAckFireRequested());
        lines.add("command_ack_accepted=" + receipt.commandAckAccepted());
        Files.write(receiptPath, lines,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
