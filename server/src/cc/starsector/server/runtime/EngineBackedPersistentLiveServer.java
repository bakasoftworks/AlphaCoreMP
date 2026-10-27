package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStateHashBoundary;
import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;
import cc.starsector.server.bridge.EngineBackedCombatWorldState;
import cc.starsector.server.bridge.EngineBackedLiveWorldSnapshotSource;
import cc.starsector.server.bridge.EngineBackedShipCommandSink;
import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.protocol.ConcurrentCommandAuthorityArbiter.ClientAuthorityIdentity;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Product entrypoint for serving a live engine-backed combat stream.
 *
 * <p>This promotes the scout wiring into {@code server/}: the
 * caller supplies the already-promoted {@link EngineCombatBinding}, while this
 * entrypoint owns the engine-backed world/step/hash/command adapters and the
 * persistent independent-tick socket runner. The method fails closed before
 * opening the socket when the stream identity does not match the bound engine
 * roster or frame contract.
 */
public final class EngineBackedPersistentLiveServer {

    private EngineBackedPersistentLiveServer() {
    }

    public static PersistentLiveServerRunner.IndependentTickLiveServerReceipt
            runIndependentTickLiveServer(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    EngineCombatBinding binding,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(streamIdentity, "streamIdentity");

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        EngineBackedCombatStateHashBoundary stateHashBoundary =
                new EngineBackedCombatStateHashBoundary(binding);
        EngineBackedShipCommandSink commandSink = new EngineBackedShipCommandSink(binding);

        requireAdmittedEngineStream(expectedFrames, world, step, streamIdentity);
        PersistentLiveServerRunner.IndependentTickLiveServerReceipt receipt =
                PersistentLiveServerRunner.runIndependentTickLiveServer(
                        receiverArtifactPath,
                        clientReceiptPath,
                        expectedFrames,
                        step,
                        stateHashBoundary,
                        movementCommand,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireReceiptMatchesAdmittedStream(receipt, expectedFrames, streamIdentity);
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickLiveServerReceipt
            runIndependentTickLiveServerOffLoopback(
                    Path receiverArtifactPath,
                    Path clientReceiptPath,
                    int expectedFrames,
                    EngineCombatBinding binding,
                    HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(movementCommand, "movementCommand");
        Objects.requireNonNull(streamIdentity, "streamIdentity");

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        EngineBackedCombatStateHashBoundary stateHashBoundary =
                new EngineBackedCombatStateHashBoundary(binding);
        EngineBackedShipCommandSink commandSink = new EngineBackedShipCommandSink(binding);

        requireAdmittedEngineStream(expectedFrames, world, step, streamIdentity);
        PersistentLiveServerRunner.IndependentTickLiveServerReceipt receipt =
                OfflineCombatStepWireLocalhostSocketTransport
                        .sendIndependentTickLiveServerOffLoopbackWithClientMovementCommand(
                                receiverArtifactPath,
                                clientReceiptPath,
                                expectedFrames,
                                step,
                                stateHashBoundary,
                                movementCommand,
                                commandSink,
                                streamIdentity);
        requireReceiptMatchesAdmittedStream(receipt, expectedFrames, streamIdentity);
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickConcurrentLiveServerReceipt
            runIndependentTickConcurrentLiveServer(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFrames,
                    EngineCombatBinding binding,
                    List<ClientAuthorityIdentity> identities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        identities = List.copyOf(Objects.requireNonNull(identities, "identities"));
        movementCommands = List.copyOf(Objects.requireNonNull(movementCommands, "movementCommands"));

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        EngineBackedCombatStateHashBoundary stateHashBoundary =
                new EngineBackedCombatStateHashBoundary(binding);
        EngineBackedShipCommandSink commandSink = new EngineBackedShipCommandSink(binding);

        requireAdmittedEngineStream(expectedFrames, world, step, streamIdentity);
        PersistentLiveServerRunner.IndependentTickConcurrentLiveServerReceipt receipt =
                PersistentLiveServerRunner.runIndependentTickConcurrentLiveServer(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFrames,
                        step,
                        stateHashBoundary,
                        identities,
                        movementCommands,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireConcurrentReceiptMatchesAdmittedStream(receipt, expectedFrames, streamIdentity);
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickReconnectLiveServerReceipt
            runIndependentTickReconnectLiveServer(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    EngineCombatBinding binding,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
                    SnapshotStreamIdentity streamIdentity,
                    InetAddress socketAddress) throws IOException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        Objects.requireNonNull(socketAddress, "socketAddress");
        movementCommands = List.copyOf(Objects.requireNonNull(movementCommands, "movementCommands"));

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        EngineBackedCombatStateHashBoundary stateHashBoundary =
                new EngineBackedCombatStateHashBoundary(binding);
        EngineBackedShipCommandSink commandSink = new EngineBackedShipCommandSink(binding);

        requireAdmittedEngineStream(expectedFramesPerConnection, world, step, streamIdentity);
        PersistentLiveServerRunner.IndependentTickReconnectLiveServerReceipt receipt =
                PersistentLiveServerRunner.runIndependentTickReconnectLiveServer(
                        receiverArtifactPaths,
                        clientReceiptPaths,
                        expectedFramesPerConnection,
                        step,
                        stateHashBoundary,
                        movementCommands,
                        commandSink,
                        streamIdentity,
                        socketAddress);
        requireReconnectReceiptMatchesAdmittedStream(
                receipt,
                expectedFramesPerConnection,
                streamIdentity);
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickReconnectLiveServerReceipt
            runIndependentTickReconnectLiveServerOffLoopback(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFramesPerConnection,
                    EngineCombatBinding binding,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        movementCommands = List.copyOf(Objects.requireNonNull(movementCommands, "movementCommands"));

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        EngineBackedCombatStateHashBoundary stateHashBoundary =
                new EngineBackedCombatStateHashBoundary(binding);
        EngineBackedShipCommandSink commandSink = new EngineBackedShipCommandSink(binding);

        requireAdmittedEngineStream(expectedFramesPerConnection, world, step, streamIdentity);
        PersistentLiveServerRunner.IndependentTickReconnectLiveServerReceipt receipt =
                OfflineCombatStepWireLocalhostSocketTransport
                        .sendIndependentTickReconnectLiveServerOffLoopbackWithClientMovementCommands(
                                receiverArtifactPaths,
                                clientReceiptPaths,
                                expectedFramesPerConnection,
                                step,
                                stateHashBoundary,
                                movementCommands,
                                commandSink,
                                streamIdentity);
        requireReconnectReceiptMatchesAdmittedStream(
                receipt,
                expectedFramesPerConnection,
                streamIdentity);
        return receipt;
    }

    public static PersistentLiveServerRunner.IndependentTickConcurrentLiveServerReceipt
            runIndependentTickConcurrentLiveServerOffLoopback(
                    List<Path> receiverArtifactPaths,
                    List<Path> clientReceiptPaths,
                    int expectedFrames,
                    EngineCombatBinding binding,
                    List<ClientAuthorityIdentity> identities,
                    List<HeadlessSnapshotSocketClient.ClientMovementCommand> movementCommands,
                    SnapshotStreamIdentity streamIdentity) throws IOException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(streamIdentity, "streamIdentity");
        identities = List.copyOf(Objects.requireNonNull(identities, "identities"));
        movementCommands = List.copyOf(Objects.requireNonNull(movementCommands, "movementCommands"));

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);
        EngineBackedCombatStateHashBoundary stateHashBoundary =
                new EngineBackedCombatStateHashBoundary(binding);
        EngineBackedShipCommandSink commandSink = new EngineBackedShipCommandSink(binding);

        requireAdmittedEngineStream(expectedFrames, world, step, streamIdentity);
        PersistentLiveServerRunner.IndependentTickConcurrentLiveServerReceipt receipt =
                OfflineCombatStepWireLocalhostSocketTransport
                        .sendIndependentTickConcurrentLiveServerOffLoopbackWithClientMovementCommands(
                                receiverArtifactPaths,
                                clientReceiptPaths,
                                expectedFrames,
                                step,
                                stateHashBoundary,
                                identities,
                                movementCommands,
                                commandSink,
                                streamIdentity);
        requireConcurrentReceiptMatchesAdmittedStream(receipt, expectedFrames, streamIdentity);
        return receipt;
    }

    /**
     * Publish a live per-entity {@code AWSS} stream from the bound engine
     * onto an off-loopback socket to a separate-JVM receiver, one snapshot per tick.
     * Unlike {@link #runIndependentTickLiveServerOffLoopback},
     * which frames the fixed-width aggregate {@code OfflineCombatStep}, this emits the
     * variable-length per-entity snapshot built from the live engine views as the
     * world advances. Fails closed before the socket when the bound roster/frame
     * contract does not match the stream identity, and after the run if the receipt
     * does not satisfy it.
     */
    public static LiveWorldSnapshotSocketPublisher.Receipt publishLiveWorldSnapshotStreamOffLoopback(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            int expectedFrames,
            float fixedDt,
            EngineCombatBinding binding,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return publishLiveWorldSnapshotStream(
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                expectedFrames,
                fixedDt,
                binding,
                streamIdentity,
                false);
    }

    /** Loopback variant of {@link #publishLiveWorldSnapshotStreamOffLoopback} for self-contained proofs. */
    public static LiveWorldSnapshotSocketPublisher.Receipt publishLiveWorldSnapshotStreamLoopback(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            int expectedFrames,
            float fixedDt,
            EngineCombatBinding binding,
            SnapshotStreamIdentity streamIdentity) throws IOException {
        return publishLiveWorldSnapshotStream(
                receiverArtifactPath,
                clientReceiptPath,
                clientLogPath,
                expectedFrames,
                fixedDt,
                binding,
                streamIdentity,
                true);
    }

    private static LiveWorldSnapshotSocketPublisher.Receipt publishLiveWorldSnapshotStream(
            Path receiverArtifactPath,
            Path clientReceiptPath,
            Path clientLogPath,
            int expectedFrames,
            float fixedDt,
            EngineCombatBinding binding,
            SnapshotStreamIdentity streamIdentity,
            boolean loopback) throws IOException {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(streamIdentity, "streamIdentity");

        EngineBackedCombatWorldState world = new EngineBackedCombatWorldState(binding);
        EngineBackedCombatStepBoundary step = new EngineBackedCombatStepBoundary(world, binding);

        requireAdmittedEngineStream(expectedFrames, world, step, streamIdentity);
        int shipCount = world.roster().shipCount();
        EngineBackedLiveWorldSnapshotSource source =
                new EngineBackedLiveWorldSnapshotSource(step, binding, expectedFrames, shipCount, fixedDt);
        LiveWorldSnapshotSocketPublisher.Receipt receipt = loopback
                ? LiveWorldSnapshotSocketPublisher.publishLoopback(
                        source, receiverArtifactPath, clientReceiptPath, clientLogPath)
                : LiveWorldSnapshotSocketPublisher.publishOffLoopback(
                        source, receiverArtifactPath, clientReceiptPath, clientLogPath);
        requireLivePublishReceiptMatchesAdmittedStream(receipt, expectedFrames, streamIdentity);
        return receipt;
    }

    private static void requireLivePublishReceiptMatchesAdmittedStream(
            LiveWorldSnapshotSocketPublisher.Receipt receipt,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity) {
        Objects.requireNonNull(receipt, "receipt");
        if (receipt.framesPublished() != expectedFrames) {
            throw new IllegalStateException("engine-backed live world snapshot frame count mismatch");
        }
        if (streamIdentity.hasShipCountRequirement()
                && receipt.entityCountFirstFrame() != streamIdentity.requiredShipCount()) {
            throw new IllegalStateException("stream requires "
                    + streamIdentity.requiredShipCount()
                    + " ships, but live snapshot frame has "
                    + receipt.entityCountFirstFrame());
        }
        if (streamIdentity.requiresStateChanges() && !receipt.stateChanges()) {
            throw new IllegalStateException("engine-backed live world snapshot stream did not change state");
        }
    }

    private static void requireAdmittedEngineStream(
            int expectedFrames,
            EngineBackedCombatWorldState world,
            EngineBackedCombatStepBoundary step,
            SnapshotStreamIdentity streamIdentity) {
        if (expectedFrames < 1) {
            throw new IllegalArgumentException("expectedFrames must be >= 1; got " + expectedFrames);
        }
        if (!step.directInnerStepReady()) {
            throw new IllegalStateException("engine-backed direct inner step is not ready");
        }
        int shipCount = world.roster().shipCount();
        if (streamIdentity.hasShipCountRequirement()
                && streamIdentity.requiredShipCount() != shipCount) {
            throw new IllegalStateException(
                    "stream requires "
                            + streamIdentity.requiredShipCount()
                            + " ships, but engine roster has "
                            + shipCount);
        }
        if (streamIdentity.hasFrameRequirement()
                && streamIdentity.requiredFrames() != expectedFrames) {
            throw new IllegalArgumentException(
                    "stream requires "
                            + streamIdentity.requiredFrames()
                            + " frames, but expectedFrames="
                            + expectedFrames);
        }
    }

    private static void requireReceiptMatchesAdmittedStream(
            PersistentLiveServerRunner.IndependentTickLiveServerReceipt receipt,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity) {
        Objects.requireNonNull(receipt, "receipt");
        List<OfflineCombatStepWireFrame> frames = receipt.processReceipt().receiverFrames();
        if (receipt.producedFrames() != expectedFrames || frames.size() != expectedFrames) {
            throw new IllegalStateException("engine-backed live server frame count mismatch");
        }
        if (streamIdentity.hasShipCountRequirement()) {
            int latestShipCount = frames.get(frames.size() - 1).result().shipCount();
            if (latestShipCount != streamIdentity.requiredShipCount()) {
                throw new IllegalStateException(
                        "stream requires "
                                + streamIdentity.requiredShipCount()
                                + " ships, but latest frame has "
                                + latestShipCount);
            }
        }
        if (streamIdentity.requiresStateChanges()
                && frames.get(0).result().stateHash()
                        == frames.get(frames.size() - 1).result().stateHash()) {
            throw new IllegalStateException("engine-backed live server stream did not change state");
        }
    }

    private static void requireReconnectReceiptMatchesAdmittedStream(
            PersistentLiveServerRunner.IndependentTickReconnectLiveServerReceipt receipt,
            int expectedFramesPerConnection,
            SnapshotStreamIdentity streamIdentity) {
        Objects.requireNonNull(receipt, "receipt");
        if (receipt.acceptedConnections() != 2) {
            throw new IllegalStateException("engine-backed reconnect live server did not accept two clients");
        }
        int expectedProducedFrames = Math.multiplyExact(expectedFramesPerConnection, 2);
        if (receipt.producedFrames() != expectedProducedFrames) {
            throw new IllegalStateException("engine-backed reconnect live server frame count mismatch");
        }
        for (int i = 0; i < receipt.connectionReceipts().size(); i++) {
            List<OfflineCombatStepWireFrame> frames = receipt.connectionReceipts().get(i).receiverFrames();
            if (frames.size() != expectedFramesPerConnection) {
                throw new IllegalStateException(
                        "engine-backed reconnect live server window " + i + " frame count mismatch");
            }
            if (streamIdentity.hasShipCountRequirement()) {
                int latestShipCount = frames.get(frames.size() - 1).result().shipCount();
                if (latestShipCount != streamIdentity.requiredShipCount()) {
                    throw new IllegalStateException(
                            "stream requires "
                                    + streamIdentity.requiredShipCount()
                                    + " ships, but reconnect window "
                                    + i
                                    + " latest frame has "
                                    + latestShipCount);
                }
            }
            if (streamIdentity.requiresStateChanges()
                    && frames.get(0).result().stateHash()
                            == frames.get(frames.size() - 1).result().stateHash()) {
                throw new IllegalStateException(
                        "engine-backed reconnect live server window " + i + " did not change state");
            }
        }
    }

    private static void requireConcurrentReceiptMatchesAdmittedStream(
            PersistentLiveServerRunner.IndependentTickConcurrentLiveServerReceipt receipt,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity) {
        Objects.requireNonNull(receipt, "receipt");
        List<OfflineCombatStepWireFrame> frames = receipt.concurrentSessionReceipt().sharedFrames();
        if (receipt.producedFrames() != expectedFrames || frames.size() != expectedFrames) {
            throw new IllegalStateException("engine-backed concurrent live server frame count mismatch");
        }
        if (receipt.acceptedConnections() != 2) {
            throw new IllegalStateException("engine-backed concurrent live server did not accept two clients");
        }
        if (streamIdentity.hasShipCountRequirement()) {
            int latestShipCount = frames.get(frames.size() - 1).result().shipCount();
            if (latestShipCount != streamIdentity.requiredShipCount()) {
                throw new IllegalStateException(
                        "stream requires "
                                + streamIdentity.requiredShipCount()
                                + " ships, but latest concurrent frame has "
                                + latestShipCount);
            }
        }
        if (streamIdentity.requiresStateChanges()
                && frames.get(0).result().stateHash()
                        == frames.get(frames.size() - 1).result().stateHash()) {
            throw new IllegalStateException(
                    "engine-backed concurrent live server stream did not change state");
        }
    }
}
