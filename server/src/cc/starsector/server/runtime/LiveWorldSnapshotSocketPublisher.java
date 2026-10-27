package cc.starsector.server.runtime;

import cc.starsector.server.client.WorldSnapshotSocketClient;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.ReliabilityLane;
import cc.starsector.server.protocol.WorldSnapshotPublicationPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Live per-tick publisher of the per-entity {@link AuthoritativeWorldSnapshot}
 * wire payload ({@code AWSS}) onto a real TCP socket, consumed by a separate-JVM
 * {@link WorldSnapshotSocketClient}.
 *
 * <p>This is the live-publish step that two earlier probes stopped
 * short of. The first accumulated every per-entity snapshot into a list and
 * wrote the whole {@code AWSS} artifact to a file <em>after</em> the step loop;
 * the second streamed that already-encoded file over a socket in one
 * {@code write}. Neither emitted snapshots <em>as the world advanced</em>. Here the
 * server pulls one {@link AuthoritativeWorldSnapshot} per tick from a
 * {@link LiveWorldSnapshotSource}, encodes it with the real
 * {@link AuthoritativeWorldSnapshotCodec}, and writes+flushes that single frame onto
 * the socket before stepping again. The bytes on the wire are produced frame-by-frame
 * by the live simulation — the genuine "real server emits one snapshot per tick onto
 * the socket" path.
 *
 * <p>The crossing is validated end to end and fail-closed: the server accumulates the
 * exact bytes it published, cross-checks the receiver's drained file for
 * byte-identity, and reconciles frame count, an independently computed
 * distinct-entity-state count, and the SHA-256 against the separate process's receipt.
 */
public final class LiveWorldSnapshotSocketPublisher {

    private static final int BACKLOG = 1;
    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;
    private static final long PROCESS_JOIN_TIMEOUT_MILLIS = 60_000L;

    private LiveWorldSnapshotSocketPublisher() {
    }

    public record Receipt(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            String socketHost,
            int socketPort,
            boolean loopback,
            int expectedFrames,
            int framesPublished,
            long bytesPublished,
            long receiverBytes,
            int entityCountFirstFrame,
            int serverDistinctEntityStates,
            int clientDistinctEntityStates,
            boolean stateChanges,
            String publishedSha256,
            int nonShipRecordsPublished,
            int framesWithNonShip,
            int maxNonShipPerFrame,
            int serverDistinctNonShipStates,
            int clientDistinctNonShipStates,
            int clientNonShipRecordsReceived,
            String clientFirstNonShipDescriptor,
            // Raw source-frame accounting taken BEFORE per-peer governance,
            // so a GovernedReceipt can prove what the registry withheld (not just what
            // it served): the un-governed source frames carried this many ships on the
            // first frame / this many non-ship records summed across frames. For a null
            // policy (every legacy caller) these equal the published counts.
            int sourceEntityCountFirstFrame,
            int sourceNonShipRecordsPublished,
            List<AuthoritativeWorldSnapshot> publishedSnapshots) {

        /** True when at least one non-ship entity (e.g. a live beam) crossed the wire. */
        public boolean nonShipCrossed() {
            return nonShipRecordsPublished > 0
                    && clientNonShipRecordsReceived == nonShipRecordsPublished;
        }
    }

    /**
     * Receipt for the closed authoritative round-trip: the base
     * publish receipt plus the inbound client intent the now-bidirectional publisher
     * read off the socket and the count of ticks on which it drove the gated
     * command-authority FIRE.
     */
    public record RoundTripReceipt(
            Receipt publish,
            boolean intentReceived,
            boolean intentFire,
            long intentShipId,
            int intentWeaponGroup,
            long inboundIntentBytes,
            int fireApplications) {

        /**
         * True when a non-ship effect (the beam) crossed back to the same client AND
         * that effect is attributable to the client's own received FIRE intent — the
         * core round-trip property. Fail-closed: false if no intent was read.
         */
        public boolean effectAttributableToClientIntent() {
            return intentReceived && intentFire && publish.nonShipCrossed() && fireApplications > 0;
        }

        /**
         * True when the control intent (hold/no fire) correctly produced no effect:
         * an intent was received, it did not request fire, and no non-ship record
         * crossed back. Proves the effect is caused by the intent, not server script.
         */
        public boolean controlSuppressedEffect() {
            return intentReceived && !intentFire
                    && publish.nonShipRecordsPublished() == 0
                    && fireApplications == 0;
        }
    }

    /**
     * Receipt for the FIRE round-trip with an explicit typed server acknowledgement:
     * the base {@link RoundTripReceipt} plus the server&rarr;client
     * {@link LiveCommandAck} the now-two-way path carries. Where the base round-trip
     * proved the client's command produced an effect (a beam) that crossed back, this
     * adds the explicit server-authored confirmation of the command — the registered
     * {@code server.ship_fire.result} message, decoded by the separate-process client and
     * cross-checked here against the command the server applied. The {@code clientAck*}
     * fields are what that separate JVM decoded off the wire and reported in its receipt.
     */
    public record AckRoundTripReceipt(
            RoundTripReceipt roundTrip,
            boolean commandAckSent,
            long commandAckBytes,
            boolean clientReportedAckReceived,
            long clientAckShipId,
            int clientAckWeaponGroup,
            boolean clientAckFireRequested,
            boolean clientAckAccepted) {

        /**
         * True when the explicit command-result feedback loop closed end to end: the
         * server read the client's FIRE intent, the live step applied it and the effect
         * crossed back, AND the separate-process client decoded the typed server ack
         * (accepted) matching that command. This is the explicit confirmation the base
         * FIRE round-trip could only infer from snapshot side-effects.
         */
        public boolean commandAckRoundTripClosed() {
            return roundTrip.effectAttributableToClientIntent()
                    && commandAckSent
                    && clientReportedAckReceived
                    && clientAckAccepted;
        }
    }

    /**
     * Receipt for the closed authoritative MANEUVER round-trip:
     * the maneuver sibling of {@link RoundTripReceipt}. The base publish receipt plus
     * the inbound {@link LiveManeuverIntent} the bidirectional publisher read off the
     * socket and the count of ticks on which it drove the gated command-authority
     * movement command.
     */
    public record ManeuverRoundTripReceipt(
            Receipt publish,
            boolean intentReceived,
            boolean intentManeuver,
            long intentShipId,
            int intentMovementOrdinal,
            long inboundIntentBytes,
            int maneuverApplications) {

        /**
         * True when the maneuver intent drove the gated command on every tick it held
         * and the live stream evolved — the structural round-trip property. The MOTION
         * effect itself (the commanded ship's velocity climbing under an ACCELERATE) is
         * re-derived from the returned ship records by the test/verifier, exactly as the
         * FIRE round-trip's beam is decoded from bytes: the receipt is the structural
         * gate, the bytes are the proof. Fail-closed: false if no intent was read.
         */
        public boolean effectAttributableToClientManeuverIntent() {
            return intentReceived && intentManeuver && maneuverApplications > 0
                    && publish.framesPublished() == maneuverApplications
                    && publish.stateChanges();
        }

        /**
         * True when the control intent (hold/no maneuver) correctly issued no command:
         * an intent was received, it did not request a maneuver, and no maneuver was
         * applied. The commanded ship's lack of acceleration is re-derived from bytes.
         */
        public boolean controlSuppressedManeuver() {
            return intentReceived && !intentManeuver && maneuverApplications == 0;
        }
    }

    /**
     * Receipt for a registry-governed publish: the base publish
     * receipt plus the {@link WorldSnapshotPublicationPolicy} decision the registry
     * resolved for the peer — the world-snapshot schema version actually served, the
     * registry-granted reliability lane, and whether each frame was relevance-scoped.
     * The base receipt's per-frame counts ({@code entityCountFirstFrame},
     * {@code nonShipRecordsPublished}) already reflect the <em>governed</em> bytes the
     * separate-JVM peer received, so a v1-only peer reads {@code nonShipRecordsPublished
     * == 0} and a relevance-scoped peer reads a reduced entity count.
     */
    public record GovernedReceipt(
            Receipt publish,
            int servedSchemaVersion,
            ReliabilityLane reliabilityLane,
            boolean relevanceScoped) {

        /**
         * The count of non-ship records the live source produced that the registry's
         * per-peer governance withheld from this peer (source total minus served total).
         * For a v1-only peer this is every beam/projectile the engine produced this
         * window — the registry dropped the whole schema-v2 non-ship tier; for an
         * unscoped v2 peer it is 0 (nothing withheld).
         */
        public int withheldNonShipRecords() {
            return publish.sourceNonShipRecordsPublished() - publish.nonShipRecordsPublished();
        }

        /**
         * The count of ship records the live source produced on the first frame that
         * the registry's per-peer governance withheld from this peer (source first-frame
         * ship count minus served first-frame ship count). This is the relevance-branch
         * counterpart of {@link #withheldNonShipRecords()}: a
         * relevance-scoped peer whose view excludes an out-of-interest ship reads a
         * positive value (that ship was dropped from its frame), while an unscoped peer
         * — or a peer whose view contains every ship — reads 0. Schema projection never
         * drops a ship (it only drops the non-ship tier), so for a non-relevance-scoped
         * governed publish this is always 0.
         */
        public int withheldShipRecordsFirstFrame() {
            return publish.sourceEntityCountFirstFrame() - publish.entityCountFirstFrame();
        }
    }

    public static Receipt publishLoopback(
            LiveWorldSnapshotSource source,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        return publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                InetAddress.getLoopbackAddress(),
                true,
                null,
                null,
                null,
                null,
                null);
    }

    public static Receipt publishOffLoopback(
            LiveWorldSnapshotSource source,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        InetAddress bindAddress = requireNonLoopbackIpv4Address();
        Receipt receipt = publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                bindAddress,
                false,
                null,
                null,
                null,
                null,
                null);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return receipt;
    }

    /**
     * Registry-governed loopback publish: each per-tick frame is
     * routed through {@code policy} before it is encoded, so the bytes the
     * separate-JVM peer receives are governed by the {@link WorldSnapshotPublicationPolicy}
     * the registry resolved for it — the served schema (v1-only peers get the non-ship
     * tier projected out) and the relevance view (out-of-interest entities dropped) —
     * rather than the raw source frame published to everyone. The returned
     * {@link GovernedReceipt} carries the served schema, the registry-granted reliability
     * lane, and the base byte-identity/frame-count crossing receipt.
     */
    public static GovernedReceipt publishGovernedLoopback(
            LiveWorldSnapshotSource source,
            WorldSnapshotPublicationPolicy policy,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        Objects.requireNonNull(policy, "policy");
        Receipt receipt = publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                InetAddress.getLoopbackAddress(),
                true,
                null,
                null,
                null,
                null,
                policy);
        return new GovernedReceipt(
                receipt,
                policy.servedSchemaVersion(),
                policy.reliabilityLane(),
                policy.relevanceView().isPresent());
    }

    /** Off-loopback variant of {@link #publishGovernedLoopback}. */
    public static GovernedReceipt publishGovernedOffLoopback(
            LiveWorldSnapshotSource source,
            WorldSnapshotPublicationPolicy policy,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        Objects.requireNonNull(policy, "policy");
        InetAddress bindAddress = requireNonLoopbackIpv4Address();
        Receipt receipt = publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                bindAddress,
                false,
                null,
                null,
                null,
                null,
                policy);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return new GovernedReceipt(
                receipt,
                policy.servedSchemaVersion(),
                policy.reliabilityLane(),
                policy.relevanceView().isPresent());
    }

    /**
     * Closed-round-trip loopback publish: the launched
     * separate-JVM client first <em>sends</em> {@code clientIntent} over the socket,
     * the bidirectional publisher reads it and {@link ClientIntentGate#receive}s it
     * into {@code gate} before the publish loop, and the live step consults the gate
     * each tick. The resulting per-tick {@code AWSS} frames cross back to that same
     * client, so the effect (the beam) it observes is the effect of its own command.
     */
    public static RoundTripReceipt publishWithClientIntentLoopback(
            LiveWorldSnapshotSource source,
            ClientIntentGate gate,
            LiveClientIntent clientIntent,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(clientIntent, "clientIntent");
        Receipt receipt = publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                InetAddress.getLoopbackAddress(),
                true,
                gate,
                clientIntent,
                null,
                null,
                null);
        return roundTripReceipt(receipt, gate);
    }

    /** Off-loopback variant of {@link #publishWithClientIntentLoopback}. */
    public static RoundTripReceipt publishWithClientIntentOffLoopback(
            LiveWorldSnapshotSource source,
            ClientIntentGate gate,
            LiveClientIntent clientIntent,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(clientIntent, "clientIntent");
        InetAddress bindAddress = requireNonLoopbackIpv4Address();
        Receipt receipt = publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                bindAddress,
                false,
                gate,
                clientIntent,
                null,
                null,
                null);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return roundTripReceipt(receipt, gate);
    }

    /**
     * Closed FIRE round-trip with an explicit typed server acknowledgement:
     * the same path as {@link #publishWithClientIntentLoopback}, but
     * the server now also writes one {@link LiveCommandAck} (the registered
     * {@code server.ship_fire.result} message) back to the client after reading its intent
     * and before the publish loop. The separate-JVM client reads and asserts that typed
     * ack before draining snapshots, so the client learns its command was accepted from an
     * explicit server message — not only by inferring the effect from the snapshot stream.
     */
    public static AckRoundTripReceipt publishWithClientIntentAndAckLoopback(
            LiveWorldSnapshotSource source,
            ClientIntentGate gate,
            LiveClientIntent clientIntent,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(clientIntent, "clientIntent");
        Receipt receipt = publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                InetAddress.getLoopbackAddress(),
                true,
                gate,
                clientIntent,
                null,
                null,
                null,
                true);
        return ackRoundTripReceipt(receipt, gate, clientIntent, clientReceiptPath);
    }

    /** Off-loopback variant of {@link #publishWithClientIntentAndAckLoopback}. */
    public static AckRoundTripReceipt publishWithClientIntentAndAckOffLoopback(
            LiveWorldSnapshotSource source,
            ClientIntentGate gate,
            LiveClientIntent clientIntent,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(clientIntent, "clientIntent");
        InetAddress bindAddress = requireNonLoopbackIpv4Address();
        Receipt receipt = publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                bindAddress,
                false,
                gate,
                clientIntent,
                null,
                null,
                null,
                true);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return ackRoundTripReceipt(receipt, gate, clientIntent, clientReceiptPath);
    }

    /**
     * Closed-round-trip loopback publish for a MANEUVER intent:
     * the maneuver sibling of {@link #publishWithClientIntentLoopback}. The launched
     * separate-JVM client first <em>sends</em> {@code maneuverIntent} over the socket,
     * the bidirectional publisher reads it and {@link ClientManeuverGate#receive}s it
     * into {@code gate} before the publish loop, and the live step consults the gate
     * each tick. The resulting per-tick {@code AWSS} frames cross back to that same
     * client, so the motion effect (the commanded ship accelerating) it observes is the
     * effect of its own command.
     */
    public static ManeuverRoundTripReceipt publishWithManeuverIntentLoopback(
            LiveWorldSnapshotSource source,
            ClientManeuverGate gate,
            LiveManeuverIntent maneuverIntent,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(maneuverIntent, "maneuverIntent");
        Receipt receipt = publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                InetAddress.getLoopbackAddress(),
                true,
                null,
                null,
                gate,
                maneuverIntent,
                null);
        return maneuverRoundTripReceipt(receipt, gate);
    }

    /** Off-loopback variant of {@link #publishWithManeuverIntentLoopback}. */
    public static ManeuverRoundTripReceipt publishWithManeuverIntentOffLoopback(
            LiveWorldSnapshotSource source,
            ClientManeuverGate gate,
            LiveManeuverIntent maneuverIntent,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath) throws IOException {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(maneuverIntent, "maneuverIntent");
        InetAddress bindAddress = requireNonLoopbackIpv4Address();
        Receipt receipt = publish(
                source,
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                bindAddress,
                false,
                null,
                null,
                gate,
                maneuverIntent,
                null);
        requireNonLoopbackSocketHost(receipt.socketHost());
        return maneuverRoundTripReceipt(receipt, gate);
    }

    private static RoundTripReceipt roundTripReceipt(Receipt receipt, ClientIntentGate gate) {
        LiveClientIntent received = gate.intentOrNull();
        return new RoundTripReceipt(
                receipt,
                gate.intentReceived(),
                received != null && received.fire(),
                gate.shipId(),
                gate.weaponGroup(),
                gate.intentReceived() ? LiveClientIntentCodec.FRAME_BYTES : 0L,
                gate.fireApplications());
    }

    private static AckRoundTripReceipt ackRoundTripReceipt(
            Receipt receipt, ClientIntentGate gate, LiveClientIntent clientIntent, Path clientReceiptPath)
            throws IOException {
        RoundTripReceipt roundTrip = roundTripReceipt(receipt, gate);
        ClientCommandAck clientAck = readClientCommandAck(clientReceiptPath);
        // Cross-process verification: the separate-JVM client must have decoded a typed ack
        // matching the command this server applied. Fail-closed on a missing or mismatched ack.
        if (!clientAck.received()) {
            throw new IOException("client did not report a decoded command ack: " + clientReceiptPath);
        }
        if (clientAck.shipId() != clientIntent.shipId()
                || clientAck.weaponGroup() != clientIntent.weaponGroup()
                || clientAck.fireRequested() != clientIntent.fire()) {
            throw new IOException("client-decoded command ack does not match the applied intent: client="
                    + clientAck + " intent=" + clientIntent);
        }
        return new AckRoundTripReceipt(
                roundTrip,
                true,
                LiveCommandAckCodec.FRAME_BYTES,
                clientAck.received(),
                clientAck.shipId(),
                clientAck.weaponGroup(),
                clientAck.fireRequested(),
                clientAck.accepted());
    }

    private static ManeuverRoundTripReceipt maneuverRoundTripReceipt(
            Receipt receipt, ClientManeuverGate gate) {
        LiveManeuverIntent received = gate.intentOrNull();
        return new ManeuverRoundTripReceipt(
                receipt,
                gate.intentReceived(),
                received != null && received.maneuver(),
                gate.shipId(),
                gate.movementOrdinal(),
                gate.intentReceived() ? LiveManeuverIntentCodec.FRAME_BYTES : 0L,
                gate.maneuverApplications());
    }

    private static Receipt publish(
            LiveWorldSnapshotSource source,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            InetAddress bindAddress,
            boolean loopback,
            ClientIntentGate gate,
            LiveClientIntent clientProcessIntent,
            ClientManeuverGate maneuverGate,
            LiveManeuverIntent maneuverProcessIntent,
            WorldSnapshotPublicationPolicy policy) throws IOException {
        return publish(source, receiverArtifactPath, clientReceiptPath, clientLogPath, bindAddress,
                loopback, gate, clientProcessIntent, maneuverGate, maneuverProcessIntent, policy, false);
    }

    private static Receipt publish(
            LiveWorldSnapshotSource source,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            InetAddress bindAddress,
            boolean loopback,
            ClientIntentGate gate,
            LiveClientIntent clientProcessIntent,
            ClientManeuverGate maneuverGate,
            LiveManeuverIntent maneuverProcessIntent,
            WorldSnapshotPublicationPolicy policy,
            boolean sendCommandAck) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        Objects.requireNonNull(clientReceiptPath, "clientReceiptPath");
        Objects.requireNonNull(clientLogPath, "clientLogPath");
        if ((gate == null) != (clientProcessIntent == null)) {
            throw new IllegalArgumentException("gate and clientProcessIntent must be set together");
        }
        if ((maneuverGate == null) != (maneuverProcessIntent == null)) {
            throw new IllegalArgumentException("maneuverGate and maneuverProcessIntent must be set together");
        }
        if (gate != null && maneuverGate != null) {
            throw new IllegalArgumentException(
                    "a client window carries either a fire intent or a maneuver intent, not both");
        }
        if (sendCommandAck && gate == null) {
            throw new IllegalArgumentException(
                    "sendCommandAck requires a fire intent gate (the ack acknowledges that command)");
        }
        int expectedFrames = source.expectedFrames();
        if (expectedFrames < 1) {
            throw new IllegalArgumentException("live publish requires at least one frame; got " + expectedFrames);
        }

        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }

        List<AuthoritativeWorldSnapshot> published = new ArrayList<>(expectedFrames);
        ByteArrayOutputStream publishedBytes = new ByteArrayOutputStream();
        // Accumulate the RAW source-frame stats (before policy governance) so
        // the receipt records what the registry withheld for this peer, not just what it
        // served. For a null policy these track the published counts exactly.
        int sourceEntityCountFirstFrame = -1;
        int sourceNonShipRecordsTotal = 0;
        Process clientProcess = null;
        IOException serverFailure = null;
        int port;
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            serverSocket.bind(new InetSocketAddress(bindAddress, 0), BACKLOG);
            port = serverSocket.getLocalPort();
            clientProcess = startClientProcess(
                    bindAddress.getHostAddress(),
                    port,
                    receiverArtifactPath,
                    clientReceiptPath,
                    expectedFrames,
                    clientLogPath,
                    clientProcessIntent,
                    maneuverProcessIntent,
                    sendCommandAck);

            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                // Bidirectional: read the one inbound client intent off the
                // socket BEFORE the publish loop and gate the live command-authority FIRE
                // on it. This is the read the publisher structurally never had — the
                // remote client's command now threads into the live step.
                if (gate != null) {
                    InputStream in = socket.getInputStream();
                    LiveClientIntent received = LiveClientIntentCodec.readIntent(in);
                    gate.receive(received);
                } else if (maneuverGate != null) {
                    // The maneuver sibling of the FIRE read — the remote
                    // client's movement command threads into the live step the same way.
                    InputStream in = socket.getInputStream();
                    LiveManeuverIntent received = LiveManeuverIntentCodec.readIntent(in);
                    maneuverGate.receive(received);
                }
                OutputStream out = socket.getOutputStream();
                if (sendCommandAck && gate != null) {
                    // The server now CONFIRMS the command it just read, rather
                    // than leaving the client to infer the effect from snapshot side-effects.
                    // It writes one typed LiveCommandAck (the registered server.ship_fire.result
                    // message) back to the client BEFORE the publish loop, so it precedes the
                    // AWSS frames on the wire. accept(): the gate read a well-formed intent for
                    // the owned ship, which is the server's authoritative admission decision.
                    LiveClientIntent acked = gate.intentOrNull();
                    LiveCommandAckCodec.writeAck(out, LiveCommandAck.accept(
                            acked.shipId(), acked.weaponGroup(), acked.fire()));
                }
                int firstEntityCount = -1;
                for (int frame = 0; frame < expectedFrames; frame++) {
                    AuthoritativeWorldSnapshot snapshot = source.publishNextTick();
                    if (snapshot == null) {
                        throw new IOException("live snapshot source returned null at frame " + frame);
                    }
                    // Record the RAW source frame (before governance) so the
                    // receipt proves what the policy withheld for this peer (e.g. a real
                    // engine beam the v1-only peer never receives).
                    if (sourceEntityCountFirstFrame < 0) {
                        sourceEntityCountFirstFrame = snapshot.entityCount();
                    }
                    sourceNonShipRecordsTotal += snapshot.nonShipEntityCount();
                    // Route the live frame through the per-peer registry
                    // governance before it is encoded, so the bytes this peer receives
                    // are the relevance-scoped, served-schema-projected frame the
                    // registry resolved for it (v1-only peers lose the non-ship tier;
                    // out-of-interest entities are dropped) rather than the raw source
                    // frame. A null policy leaves the frame untouched (legacy behavior).
                    if (policy != null) {
                        snapshot = policy.govern(snapshot);
                        if (snapshot == null) {
                            throw new IOException(
                                    "publication policy produced a null governed snapshot at frame " + frame);
                        }
                    }
                    if (firstEntityCount < 0) {
                        firstEntityCount = snapshot.entityCount();
                    }
                    // Encode this single tick, then write+flush it onto the socket
                    // before the next step runs: one frame per tick, produced live.
                    ByteArrayOutputStream frameBytes = new ByteArrayOutputStream();
                    AuthoritativeWorldSnapshotCodec.writeFrame(frameBytes, snapshot);
                    byte[] encoded = frameBytes.toByteArray();
                    out.write(encoded);
                    out.flush();
                    publishedBytes.write(encoded);
                    published.add(snapshot);
                }
                socket.shutdownOutput();
            } catch (IOException ioe) {
                serverFailure = ioe;
            }
        }

        int exitCode = waitForClientProcess(clientProcess, clientLogPath);
        if (serverFailure != null) {
            throw serverFailure;
        }
        if (exitCode != 0) {
            throw new IOException("live world snapshot client process exited " + exitCode
                    + "; log=" + clientLogPath.toAbsolutePath().normalize());
        }
        if (published.size() != expectedFrames) {
            throw new IOException("live publish produced " + published.size()
                    + " frames, expected " + expectedFrames);
        }

        byte[] sent = publishedBytes.toByteArray();
        byte[] receiverBytes = Files.readAllBytes(receiverArtifactPath);
        if (!Arrays.equals(sent, receiverBytes)) {
            throw new IOException("live world snapshot receiver bytes differ from published bytes");
        }
        int serverDistinct = AuthoritativeWorldSnapshotSocketTransport.distinctEntityStateCount(published);
        int serverDistinctNonShip = AuthoritativeWorldSnapshotSocketTransport.distinctNonShipStateCount(published);
        int nonShipRecordsPublished = 0;
        int framesWithNonShip = 0;
        int maxNonShipPerFrame = 0;
        for (AuthoritativeWorldSnapshot snapshot : published) {
            int count = snapshot.nonShipEntityCount();
            nonShipRecordsPublished += count;
            if (count > 0) {
                framesWithNonShip++;
                maxNonShipPerFrame = Math.max(maxNonShipPerFrame, count);
            }
        }
        String publishedSha256 = sha256Hex(sent);

        ClientReceipt clientReceipt = readClientReceipt(clientReceiptPath);
        if (clientReceipt.framesReceived() != expectedFrames) {
            throw new IOException("live world snapshot client frame count mismatch: expected "
                    + expectedFrames + ", got " + clientReceipt.framesReceived());
        }
        if (clientReceipt.bytesReceived() != sent.length) {
            throw new IOException("live world snapshot client byte count mismatch: expected "
                    + sent.length + ", got " + clientReceipt.bytesReceived());
        }
        if (clientReceipt.distinctEntityStates() != serverDistinct) {
            throw new IOException("live world snapshot distinct-entity-state mismatch: server "
                    + serverDistinct + " vs client " + clientReceipt.distinctEntityStates());
        }
        if (clientReceipt.receivedSha256() != null
                && !clientReceipt.receivedSha256().equals(publishedSha256)) {
            throw new IOException("live world snapshot SHA-256 mismatch: server " + publishedSha256
                    + " vs client " + clientReceipt.receivedSha256());
        }
        // The non-ship tier must survive the socket into the separate JVM
        // exactly as published, so the beam crossing is proven by the receiver, not
        // just claimed by the sender. -1 means an older client wrote no such field.
        if (clientReceipt.nonShipRecordsReceived() >= 0
                && clientReceipt.nonShipRecordsReceived() != nonShipRecordsPublished) {
            throw new IOException("live world snapshot non-ship record count mismatch: server "
                    + nonShipRecordsPublished + " vs client " + clientReceipt.nonShipRecordsReceived());
        }
        if (clientReceipt.distinctNonShipStates() >= 0
                && clientReceipt.distinctNonShipStates() != serverDistinctNonShip) {
            throw new IOException("live world snapshot distinct-non-ship-state mismatch: server "
                    + serverDistinctNonShip + " vs client " + clientReceipt.distinctNonShipStates());
        }

        return new Receipt(
                receiverArtifactPath,
                clientReceiptPath,
                bindAddress.getHostAddress(),
                port,
                loopback,
                expectedFrames,
                published.size(),
                sent.length,
                receiverBytes.length,
                published.get(0).entityCount(),
                serverDistinct,
                clientReceipt.distinctEntityStates(),
                clientReceipt.stateChanges(),
                publishedSha256,
                nonShipRecordsPublished,
                framesWithNonShip,
                maxNonShipPerFrame,
                serverDistinctNonShip,
                clientReceipt.distinctNonShipStates(),
                clientReceipt.nonShipRecordsReceived(),
                clientReceipt.firstNonShipDescriptor(),
                sourceEntityCountFirstFrame,
                sourceNonShipRecordsTotal,
                List.copyOf(published));
    }

    private static Process startClientProcess(
            String host,
            int port,
            Path receiverArtifactPath,
            Path clientReceiptPath,
            int expectedFrames,
            Path clientLogPath,
            LiveClientIntent clientProcessIntent,
            LiveManeuverIntent maneuverProcessIntent,
            boolean expectCommandAck) throws IOException {
        Path logParent = clientLogPath.getParent();
        if (logParent != null) {
            Files.createDirectories(logParent);
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IOException("java.home is not available for launching live world snapshot client process");
        }
        String javaBinary = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new IOException("java.class.path is not available for launching live world snapshot client process");
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", javaBinary).toString());
        command.add("-cp");
        command.add(classPath);
        command.add(WorldSnapshotSocketClient.class.getName());
        command.add("--host");
        command.add(host);
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--artifact");
        command.add(receiverArtifactPath.toAbsolutePath().normalize().toString());
        command.add("--expected-frames");
        command.add(Integer.toString(expectedFrames));
        command.add("--receipt");
        command.add(clientReceiptPath.toAbsolutePath().normalize().toString());
        if (clientProcessIntent != null) {
            // Instruct the spawned client to SEND this intent over the
            // socket before draining snapshots. The server reads it back off the wire.
            command.add("--send-intent-fire");
            command.add(Boolean.toString(clientProcessIntent.fire()));
            command.add("--send-intent-ship");
            command.add(Long.toString(clientProcessIntent.shipId()));
            command.add("--send-intent-group");
            command.add(Integer.toString(clientProcessIntent.weaponGroup()));
        }
        if (maneuverProcessIntent != null) {
            // The maneuver sibling — instruct the spawned client to SEND this
            // movement intent over the socket before draining snapshots.
            command.add("--send-maneuver");
            command.add(Boolean.toString(maneuverProcessIntent.maneuver()));
            command.add("--send-maneuver-ship");
            command.add(Long.toString(maneuverProcessIntent.shipId()));
            command.add("--send-maneuver-movement");
            command.add(Integer.toString(maneuverProcessIntent.movementOrdinal()));
        }
        if (expectCommandAck) {
            // Instruct the spawned client to READ the typed server command ack
            // off the socket (before draining snapshots) and assert it matches its intent.
            command.add("--expect-command-ack");
            command.add("true");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(clientLogPath.toFile());
        return builder.start();
    }

    private static int waitForClientProcess(Process clientProcess, Path clientLogPath) throws IOException {
        if (clientProcess == null) {
            throw new IOException("live world snapshot client process was not started");
        }
        try {
            if (!clientProcess.waitFor(PROCESS_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                clientProcess.destroyForcibly();
                throw new IOException("timed out waiting for live world snapshot client process; log="
                        + clientLogPath.toAbsolutePath().normalize());
            }
            return clientProcess.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for live world snapshot client process", interrupted);
        }
    }

    private record ClientReceipt(
            long bytesReceived,
            int framesReceived,
            int distinctEntityStates,
            boolean stateChanges,
            String receivedSha256,
            int nonShipRecordsReceived,
            int distinctNonShipStates,
            String firstNonShipDescriptor) {
    }

    private static ClientReceipt readClientReceipt(Path receiptPath) throws IOException {
        long bytesReceived = -1L;
        int framesReceived = -1;
        int distinctEntityStates = -1;
        boolean stateChanges = false;
        String receivedSha256 = null;
        int nonShipRecordsReceived = -1;
        int distinctNonShipStates = -1;
        String firstNonShipDescriptor = "";
        for (String line : Files.readAllLines(receiptPath)) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "bytes_received" -> bytesReceived = Long.parseLong(value);
                case "frames_received" -> framesReceived = Integer.parseInt(value);
                case "distinct_entity_states" -> distinctEntityStates = Integer.parseInt(value);
                case "state_changes" -> stateChanges = Boolean.parseBoolean(value);
                case "received_sha256" -> receivedSha256 = value;
                case "non_ship_records_received" -> nonShipRecordsReceived = Integer.parseInt(value);
                case "distinct_non_ship_states" -> distinctNonShipStates = Integer.parseInt(value);
                case "first_non_ship_descriptor" -> firstNonShipDescriptor = value;
                default -> { /* ignore other keys */ }
            }
        }
        if (bytesReceived < 0 || framesReceived < 0 || distinctEntityStates < 0) {
            throw new IOException("live world snapshot client receipt missing required fields: " + receiptPath);
        }
        return new ClientReceipt(bytesReceived, framesReceived, distinctEntityStates, stateChanges,
                receivedSha256, nonShipRecordsReceived, distinctNonShipStates, firstNonShipDescriptor);
    }

    /** The command-ack fields the separate-JVM client reported in its receipt. */
    private record ClientCommandAck(
            boolean received, long shipId, int weaponGroup, boolean fireRequested, boolean accepted) {
    }

    private static ClientCommandAck readClientCommandAck(Path receiptPath) throws IOException {
        boolean received = false;
        long shipId = -1L;
        int weaponGroup = -1;
        boolean fireRequested = false;
        boolean accepted = false;
        for (String line : Files.readAllLines(receiptPath)) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "command_ack_received" -> received = Boolean.parseBoolean(value);
                case "command_ack_ship_id" -> shipId = Long.parseLong(value);
                case "command_ack_weapon_group" -> weaponGroup = Integer.parseInt(value);
                case "command_ack_fire_requested" -> fireRequested = Boolean.parseBoolean(value);
                case "command_ack_accepted" -> accepted = Boolean.parseBoolean(value);
                default -> { /* ignore other keys */ }
            }
        }
        return new ClientCommandAck(received, shipId, weaponGroup, fireRequested, accepted);
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

    private static InetAddress requireNonLoopbackIpv4Address() throws IOException {
        InetAddress fallback = null;
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            throw new IOException("failed to enumerate local network interfaces", e);
        }
        if (interfaces == null) {
            throw new IOException("no network interfaces are available for off-loopback publish");
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            try {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
            } catch (SocketException e) {
                continue;
            }
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address)
                        || address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()) {
                    continue;
                }
                if (address.isSiteLocalAddress()) {
                    return address;
                }
                if (fallback == null) {
                    fallback = address;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IOException("no non-loopback IPv4 address is available for off-loopback publish");
    }

    private static void requireNonLoopbackSocketHost(String socketHost) throws IOException {
        Objects.requireNonNull(socketHost, "socketHost");
        InetAddress address = InetAddress.getByName(socketHost);
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            throw new IOException("off-loopback publish resolved to loopback host: " + socketHost);
        }
    }
}
