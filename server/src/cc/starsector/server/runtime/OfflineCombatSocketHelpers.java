package cc.starsector.server.runtime;

import cc.starsector.server.client.HeadlessSnapshotSocketClient;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class DirectClientRunner implements Runnable {
    private final HeadlessSnapshotSocketClient client;
    private final String host;
    private final int port;
    private final Path receiverArtifactPath;
    private final int expectedFrames;
    private final SnapshotStreamIdentity streamIdentity;
    private final Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer;
    private final HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand;
    private volatile IOException failure;
    private volatile RuntimeException runtimeFailure;
    private volatile HeadlessSnapshotSocketClient.Receipt receipt;

    DirectClientRunner(
            HeadlessSnapshotSocketClient client,
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer) {
        this(client, host, port, receiverArtifactPath, expectedFrames, genericStreamIdentity(), receiverFrameConsumer, null);
    }

    DirectClientRunner(
            HeadlessSnapshotSocketClient client,
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer) {
        this(client, host, port, receiverArtifactPath, expectedFrames, streamIdentity, receiverFrameConsumer, null);
    }

    DirectClientRunner(
            HeadlessSnapshotSocketClient client,
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand) {
        this(client, host, port, receiverArtifactPath, expectedFrames, genericStreamIdentity(),
                receiverFrameConsumer, movementCommand);
    }

    DirectClientRunner(
            HeadlessSnapshotSocketClient client,
            String host,
            int port,
            Path receiverArtifactPath,
            int expectedFrames,
            SnapshotStreamIdentity streamIdentity,
            Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer,
            HeadlessSnapshotSocketClient.ClientMovementCommand movementCommand) {
        this.client = Objects.requireNonNull(client, "client");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        this.expectedFrames = expectedFrames;
        this.streamIdentity = Objects.requireNonNull(streamIdentity, "streamIdentity");
        this.receiverFrameConsumer = Objects.requireNonNull(receiverFrameConsumer, "receiverFrameConsumer");
        this.movementCommand = movementCommand;
    }

    @Override
    public void run() {
        try {
            if (movementCommand == null) {
                receipt = client.connectAndRead(
                        host,
                        port,
                        receiverArtifactPath,
                        expectedFrames,
                        streamIdentity,
                        receiverFrameConsumer);
            } else {
                receipt = client.connectAndReadWithMovementCommand(
                        host,
                        port,
                        receiverArtifactPath,
                        expectedFrames,
                        receiverFrameConsumer,
                        movementCommand,
                        streamIdentity);
            }
        } catch (IOException ioe) {
            failure = ioe;
        } catch (RuntimeException rte) {
            runtimeFailure = rte;
        }
    }

    IOException failure() {
        return failure;
    }

    RuntimeException runtimeFailure() {
        return runtimeFailure;
    }

    HeadlessSnapshotSocketClient.Receipt receipt() {
        return receipt;
    }

    private static SnapshotStreamIdentity genericStreamIdentity() {
        return NetworkMessageRegistry.authoritativeCombat().offlineCombatStepSnapshotStreamIdentity();
    }
}

final class AdmissionClientRunner implements Runnable {
    private final HeadlessSnapshotSocketClient client;
    private final String host;
    private final int port;
    private final int expectedFrames;
    private volatile IOException failure;
    private volatile RuntimeException runtimeFailure;
    private volatile HeadlessSnapshotSocketClient.AdmissionReceipt receipt;

    AdmissionClientRunner(
            HeadlessSnapshotSocketClient client,
            String host,
            int port,
            int expectedFrames) {
        this.client = Objects.requireNonNull(client, "client");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.expectedFrames = expectedFrames;
    }

    @Override
    public void run() {
        try {
            receipt = client.connectForAdmission(host, port, expectedFrames);
        } catch (IOException ioe) {
            failure = ioe;
        } catch (RuntimeException rte) {
            runtimeFailure = rte;
        }
    }

    IOException failure() {
        return failure;
    }

    RuntimeException runtimeFailure() {
        return runtimeFailure;
    }

    HeadlessSnapshotSocketClient.AdmissionReceipt receipt() {
        return receipt;
    }
}

final class DirectReceiver implements Runnable {
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private final ServerSocket serverSocket;
    private final Path receiverArtifactPath;
    private final long expectedBytes;
    private final Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer;
    private volatile IOException failure;
    private volatile RuntimeException runtimeFailure;
    private volatile long bytesReceived;
    private volatile List<OfflineCombatStepWireFrame> frames = List.of();

    DirectReceiver(
            ServerSocket serverSocket,
            Path receiverArtifactPath,
            long expectedBytes,
            Consumer<? super OfflineCombatStepWireFrame> receiverFrameConsumer) {
        this.serverSocket = serverSocket;
        this.receiverArtifactPath = receiverArtifactPath;
        this.expectedBytes = expectedBytes;
        this.receiverFrameConsumer = Objects.requireNonNull(receiverFrameConsumer, "receiverFrameConsumer");
    }

    @Override
    public void run() {
        try (Socket socket = serverSocket.accept()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            try (InputStream input = socket.getInputStream();
                    OutputStream artifactOutput = Files.newOutputStream(
                            receiverArtifactPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                BoundedTeeInputStream directInput =
                        new BoundedTeeInputStream(input, artifactOutput, expectedBytes);
                try {
                    List<OfflineCombatStepWireFrame> decodedFrames = new ArrayList<>();
                    OfflineCombatStepWireCodec.readFrames(directInput, frame -> {
                        decodedFrames.add(frame);
                        receiverFrameConsumer.accept(frame);
                    });
                    frames = List.copyOf(decodedFrames);
                } finally {
                    bytesReceived = directInput.bytesRead();
                }
                artifactOutput.flush();
            }
        } catch (IOException ioe) {
            failure = ioe;
        } catch (RuntimeException rte) {
            runtimeFailure = rte;
        }
    }

    void rethrowIfFailed() throws IOException {
        if (failure != null) {
            throw failure;
        }
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (bytesReceived != expectedBytes) {
            throw new IOException("offline step direct wire socket receiver read unexpected byte count: expected "
                    + expectedBytes + ", got " + bytesReceived);
        }
    }

    long bytesReceived() {
        return bytesReceived;
    }

    List<OfflineCombatStepWireFrame> frames() {
        return List.copyOf(frames);
    }
}

final class BoundedTeeInputStream extends InputStream {
    private final InputStream delegate;
    private final OutputStream tee;
    private final long expectedBytes;
    private long bytesRead;

    BoundedTeeInputStream(InputStream delegate, OutputStream tee, long expectedBytes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tee = Objects.requireNonNull(tee, "tee");
        this.expectedBytes = expectedBytes;
    }

    @Override
    public int read() throws IOException {
        int value = delegate.read();
        if (value < 0) {
            return -1;
        }
        countBytes(1);
        tee.write(value);
        return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        int boundedLen = len;
        long remainingBeforeOverflow = expectedBytes - bytesRead;
        if (remainingBeforeOverflow >= 0L && remainingBeforeOverflow < boundedLen) {
            boundedLen = (int) Math.min((long) Integer.MAX_VALUE, remainingBeforeOverflow + 1L);
        }
        int read = delegate.read(b, off, boundedLen);
        if (read < 0) {
            return -1;
        }
        countBytes(read);
        tee.write(b, off, read);
        return read;
    }

    private void countBytes(int read) throws IOException {
        bytesRead += read;
        if (bytesRead > expectedBytes) {
            throw new IOException("offline step direct wire socket receiver byte bound exceeded");
        }
    }

    long bytesRead() {
        return bytesRead;
    }
}

final class FanoutDirectSocketSink implements OfflineCombatStepSink {
    private final List<CountingOutputStream> outputs;
    private final List<HeadlessSnapshotSocketClient.ClientHandshake> handshakes;
    private final int maxFrames;
    private final List<OfflineCombatStepWireFrame> frames = new ArrayList<>();
    private final List<HeadlessSnapshotSocketClient.ServerInitialSync> initialSyncs;
    private boolean finished;
    private long frameBytesWrittenPerClient;

    FanoutDirectSocketSink(
            List<OutputStream> outputs,
            List<HeadlessSnapshotSocketClient.ClientHandshake> handshakes,
            int maxFrames) {
        Objects.requireNonNull(outputs, "outputs");
        Objects.requireNonNull(handshakes, "handshakes");
        if (outputs.size() < 2) {
            throw new IllegalArgumentException("fanout socket sink requires at least two outputs");
        }
        if (handshakes.size() != outputs.size()) {
            throw new IllegalArgumentException("fanout handshakes must match outputs");
        }
        this.outputs = new ArrayList<>(outputs.size());
        for (OutputStream output : outputs) {
            this.outputs.add(new CountingOutputStream(Objects.requireNonNull(output, "output")));
        }
        this.handshakes = List.copyOf(handshakes);
        this.maxFrames = maxFrames;
        this.initialSyncs = new ArrayList<>(outputs.size());
    }

    @Override
    public void publish(OfflineCombatStepResult result) {
        Objects.requireNonNull(result, "result");
        if (finished) {
            throw new IllegalStateException("offline step fanout direct wire socket sink is finished");
        }
        if (frames.size() >= maxFrames) {
            throw new IllegalStateException("offline step fanout direct wire socket frame bound exceeded");
        }
        try {
            long sequence = frames.size();
            OfflineCombatStepWireFrame referenceFrame = null;
            for (int i = 0; i < outputs.size(); i++) {
                CountingOutputStream output = outputs.get(i);
                if (sequence == 0L) {
                    HeadlessSnapshotSocketClient.ServerInitialSync initialSync =
                            HeadlessSnapshotSocketClient.ServerInitialSync.fromSnapshot(
                                    handshakes.get(i),
                                    sequence,
                                    result);
                    HeadlessSnapshotSocketClient.writeInitialSync(output, initialSync);
                    initialSyncs.add(initialSync);
                }
                long beforeBytes = output.bytesWritten();
                OfflineCombatStepWireFrame frame =
                        OfflineCombatStepWireCodec.writeFrame(output, sequence, result);
                long frameBytes = output.bytesWritten() - beforeBytes;
                if (frameBytes != OfflineCombatStepWireCodec.FRAME_BYTES) {
                    throw new IOException("offline step fanout direct wire socket client " + i
                            + " frame byte count mismatch: expected "
                            + OfflineCombatStepWireCodec.FRAME_BYTES + ", got " + frameBytes);
                }
                if (referenceFrame == null) {
                    referenceFrame = frame;
                } else if (!referenceFrame.equals(frame)) {
                    throw new IOException("offline step fanout direct wire socket client " + i
                            + " frame diverged from first client");
                }
            }
            frameBytesWrittenPerClient += OfflineCombatStepWireCodec.FRAME_BYTES;
            frames.add(referenceFrame);
        } catch (IOException ioe) {
            throw new UncheckedIOException("failed to write fanout direct offline step wire socket frame", ioe);
        }
    }

    void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        if (frames.size() != maxFrames) {
            throw new IOException("offline step fanout direct wire socket frame count mismatch: expected "
                    + maxFrames + ", got " + frames.size());
        }
        long expectedBytes = Math.multiplyExact((long) maxFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        if (initialSyncs.size() != outputs.size()) {
            throw new IOException("offline step fanout direct wire socket initial sync was not written");
        }
        for (int i = 0; i < outputs.size(); i++) {
            CountingOutputStream output = outputs.get(i);
            output.flush();
            long expectedTotalBytes = expectedBytes + HeadlessSnapshotSocketClient.INITIAL_SYNC_BYTES;
            if (output.bytesWritten() != expectedTotalBytes) {
                throw new IOException("offline step fanout direct wire socket client " + i
                        + " byte count mismatch: expected "
                        + expectedTotalBytes + ", got " + output.bytesWritten());
            }
        }
    }

    long bytesWrittenPerClient() {
        return frameBytesWrittenPerClient;
    }

    List<OfflineCombatStepWireFrame> frames() {
        return List.copyOf(frames);
    }

    List<HeadlessSnapshotSocketClient.ServerInitialSync> initialSyncs() {
        return List.copyOf(initialSyncs);
    }
}

final class DirectSocketSink implements OfflineCombatStepSink {
    private final CountingOutputStream output;
    private final HeadlessSnapshotSocketClient.ClientHandshake handshake;
    private final int maxFrames;
    private final List<OfflineCombatStepWireFrame> frames = new ArrayList<>();
    private HeadlessSnapshotSocketClient.ServerInitialSync initialSync;
    private long frameBytesWritten;
    private boolean finished;

    DirectSocketSink(
            OutputStream output,
            HeadlessSnapshotSocketClient.ClientHandshake handshake,
            int maxFrames) {
        this.output = new CountingOutputStream(Objects.requireNonNull(output, "output"));
        this.handshake = Objects.requireNonNull(handshake, "handshake");
        this.maxFrames = maxFrames;
    }

    @Override
    public void publish(OfflineCombatStepResult result) {
        Objects.requireNonNull(result, "result");
        if (finished) {
            throw new IllegalStateException("offline step direct wire socket sink is finished");
        }
        if (frames.size() >= maxFrames) {
            throw new IllegalStateException("offline step direct wire socket frame bound exceeded");
        }
        try {
            if (frames.isEmpty()) {
                initialSync = HeadlessSnapshotSocketClient.ServerInitialSync.fromSnapshot(
                        handshake,
                        0L,
                        result);
                HeadlessSnapshotSocketClient.writeInitialSync(output, initialSync);
            }
            long beforeBytes = output.bytesWritten();
            OfflineCombatStepWireFrame frame =
                    OfflineCombatStepWireCodec.writeFrame(output, frames.size(), result);
            long frameBytes = output.bytesWritten() - beforeBytes;
            if (frameBytes != OfflineCombatStepWireCodec.FRAME_BYTES) {
                throw new IOException("offline step direct wire socket frame byte count mismatch: expected "
                        + OfflineCombatStepWireCodec.FRAME_BYTES + ", got " + frameBytes);
            }
            frameBytesWritten += frameBytes;
            frames.add(frame);
        } catch (IOException ioe) {
            throw new UncheckedIOException("failed to write direct offline step wire socket frame", ioe);
        }
    }

    void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        output.flush();
        if (frames.size() != maxFrames) {
            throw new IOException("offline step direct wire socket frame count mismatch: expected "
                    + maxFrames + ", got " + frames.size());
        }
        if (initialSync == null) {
            throw new IOException("offline step direct wire socket initial sync was not written");
        }
        long expectedBytes = Math.multiplyExact((long) maxFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        if (frameBytesWritten != expectedBytes) {
            throw new IOException("offline step direct wire socket byte count mismatch: expected "
                    + expectedBytes + ", got " + frameBytesWritten);
        }
    }

    long bytesWritten() {
        return frameBytesWritten;
    }

    List<OfflineCombatStepWireFrame> frames() {
        return List.copyOf(frames);
    }

    HeadlessSnapshotSocketClient.ServerInitialSync initialSync() {
        return initialSync;
    }
}

final class CountingOutputStream extends OutputStream {
    private final OutputStream delegate;
    private long bytesWritten;

    CountingOutputStream(OutputStream delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
        bytesWritten++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
        bytesWritten += len;
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    long bytesWritten() {
        return bytesWritten;
    }
}

final class Receiver implements Runnable {
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private final ServerSocket serverSocket;
    private final Path receiverArtifactPath;
    private final long expectedBytes;
    private volatile IOException failure;
    private volatile long bytesWritten;

    Receiver(ServerSocket serverSocket, Path receiverArtifactPath, long expectedBytes) {
        this.serverSocket = serverSocket;
        this.receiverArtifactPath = receiverArtifactPath;
        this.expectedBytes = expectedBytes;
    }

    @Override
    public void run() {
        try (Socket socket = serverSocket.accept()) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            try (InputStream input = socket.getInputStream();
                    OutputStream output = Files.newOutputStream(
                            receiverArtifactPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                bytesWritten = copyBounded(input, output, expectedBytes);
            }
        } catch (IOException ioe) {
            failure = ioe;
        }
    }

    void rethrowIfFailed() throws IOException {
        if (failure != null) {
            throw failure;
        }
        if (bytesWritten != expectedBytes) {
            throw new IOException("offline step wire socket receiver wrote unexpected byte count: expected "
                    + expectedBytes + ", got " + bytesWritten);
        }
    }

    private static long copyBounded(InputStream input, OutputStream output, long expectedBytes)
            throws IOException {
        long written = 0L;
        byte[] buffer = new byte[8192];
        while (true) {
            long remainingBeforeOverflow = expectedBytes - written;
            int maxRead = buffer.length;
            if (remainingBeforeOverflow >= 0L && remainingBeforeOverflow < maxRead) {
                maxRead = (int) Math.min((long) Integer.MAX_VALUE, remainingBeforeOverflow + 1L);
            }
            int read = input.read(buffer, 0, maxRead);
            if (read < 0) {
                return written;
            }
            written += read;
            if (written > expectedBytes) {
                throw new IOException("offline step wire socket byte bound exceeded");
            }
            output.write(buffer, 0, read);
        }
    }
}
