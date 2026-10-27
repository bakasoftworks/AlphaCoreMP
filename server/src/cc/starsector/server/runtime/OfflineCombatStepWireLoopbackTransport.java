package cc.starsector.server.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Bounded local transport for already-encoded offline combat step frames.
 *
 * <p>The sender side reads the product wire artifact bytes and writes them
 * through a pipe. The receiver side owns its readback artifact and validates it
 * with {@link OfflineCombatStepWireCodec}. This keeps post-rung-9 work at the
 * narrow byte boundary without reopening the frozen protocol facade chain.
 */
public final class OfflineCombatStepWireLoopbackTransport {

    private static final int PIPE_BUFFER_BYTES = 8192;
    private static final long RECEIVER_JOIN_TIMEOUT_MILLIS = 10_000L;

    private OfflineCombatStepWireLoopbackTransport() {
    }

    public static Receipt send(Path senderArtifactPath, Path receiverArtifactPath, int expectedFrames)
            throws IOException {
        Objects.requireNonNull(senderArtifactPath, "senderArtifactPath");
        Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
        if (expectedFrames < 0) {
            throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
        }
        if (senderArtifactPath.toAbsolutePath().normalize()
                .equals(receiverArtifactPath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("receiver artifact path must be distinct from sender artifact path");
        }

        long expectedBytes = Math.multiplyExact((long) expectedFrames, (long) OfflineCombatStepWireCodec.FRAME_BYTES);
        long senderBytes = Files.size(senderArtifactPath);
        if (senderBytes != expectedBytes) {
            throw new IOException("offline step wire transport sender byte count mismatch: expected "
                    + expectedBytes + ", got " + senderBytes);
        }

        Path receiverParent = receiverArtifactPath.getParent();
        if (receiverParent != null) {
            Files.createDirectories(receiverParent);
        }

        Receiver receiver;
        Thread receiverThread;
        IOException senderFailure = null;
        try (PipedInputStream receiverInput = new PipedInputStream(PIPE_BUFFER_BYTES)) {
            PipedOutputStream senderOutput = new PipedOutputStream(receiverInput);
            receiver = new Receiver(receiverInput, receiverArtifactPath, expectedBytes);
            receiverThread = new Thread(receiver, "offline-step-wire-loopback-receiver");
            receiverThread.setDaemon(true);
            receiverThread.start();
            try (InputStream source = Files.newInputStream(senderArtifactPath)) {
                copyBounded(source, senderOutput, expectedBytes);
            } catch (IOException ioe) {
                senderFailure = ioe;
            } finally {
                try {
                    senderOutput.close();
                } catch (IOException closeFailure) {
                    if (senderFailure == null) {
                        senderFailure = closeFailure;
                    } else {
                        senderFailure.addSuppressed(closeFailure);
                    }
                }
            }
            joinReceiver(receiverThread);
        }

        if (senderFailure != null) {
            throw senderFailure;
        }
        receiver.rethrowIfFailed();

        long receiverBytes = Files.size(receiverArtifactPath);
        if (receiverBytes != expectedBytes) {
            throw new IOException("offline step wire transport receiver byte count mismatch: expected "
                    + expectedBytes + ", got " + receiverBytes);
        }
        List<OfflineCombatStepWireFrame> receiverFrames =
                OfflineCombatStepWireCodec.readFrames(receiverArtifactPath);
        if (receiverFrames.size() != expectedFrames) {
            throw new IOException("offline step wire transport receiver frame count mismatch: expected "
                    + expectedFrames + ", got " + receiverFrames.size());
        }
        return new Receipt(
                senderArtifactPath,
                receiverArtifactPath,
                expectedFrames,
                expectedBytes,
                senderBytes,
                receiverBytes,
                receiverFrames);
    }

    private static long copyBounded(InputStream input, OutputStream output, long expectedBytes)
            throws IOException {
        byte[] buffer = new byte[OfflineCombatStepWireCodec.FRAME_BYTES];
        long total = 0L;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            total += read;
            if (total > expectedBytes) {
                throw new IOException("offline step wire transport byte bound exceeded");
            }
            output.write(buffer, 0, read);
        }
        output.flush();
        if (total != expectedBytes) {
            throw new IOException("offline step wire transport byte count mismatch: expected "
                    + expectedBytes + ", got " + total);
        }
        return total;
    }

    private static void joinReceiver(Thread receiverThread) throws IOException {
        try {
            receiverThread.join(RECEIVER_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for offline step wire receiver", interrupted);
        }
        if (receiverThread.isAlive()) {
            receiverThread.interrupt();
            throw new IOException("timed out waiting for offline step wire receiver");
        }
    }

    public record Receipt(
            Path senderArtifactPath,
            Path receiverArtifactPath,
            int expectedFrames,
            long expectedBytes,
            long senderBytes,
            long receiverBytes,
            List<OfflineCombatStepWireFrame> receiverFrames) {

        public Receipt {
            senderArtifactPath = Objects.requireNonNull(senderArtifactPath, "senderArtifactPath");
            receiverArtifactPath = Objects.requireNonNull(receiverArtifactPath, "receiverArtifactPath");
            receiverFrames = List.copyOf(Objects.requireNonNull(receiverFrames, "receiverFrames"));
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (expectedBytes < 0L) {
                throw new IllegalArgumentException("expectedBytes must be >= 0; got " + expectedBytes);
            }
            if (senderBytes != expectedBytes) {
                throw new IllegalArgumentException("senderBytes must equal expectedBytes");
            }
            if (receiverBytes != expectedBytes) {
                throw new IllegalArgumentException("receiverBytes must equal expectedBytes");
            }
            if (receiverFrames.size() != expectedFrames) {
                throw new IllegalArgumentException("receiver frame count must equal expectedFrames");
            }
        }
    }

    private static final class Receiver implements Runnable {
        private final InputStream input;
        private final Path receiverArtifactPath;
        private final long expectedBytes;
        private volatile IOException failure;
        private volatile long bytesWritten;

        private Receiver(InputStream input, Path receiverArtifactPath, long expectedBytes) {
            this.input = input;
            this.receiverArtifactPath = receiverArtifactPath;
            this.expectedBytes = expectedBytes;
        }

        @Override
        public void run() {
            try (OutputStream output = Files.newOutputStream(
                    receiverArtifactPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                bytesWritten = copyBounded(input, output, expectedBytes);
            } catch (IOException ioe) {
                failure = ioe;
            }
        }

        private void rethrowIfFailed() throws IOException {
            if (failure != null) {
                throw failure;
            }
            if (bytesWritten != expectedBytes) {
                throw new IOException("offline step wire receiver wrote unexpected byte count: expected "
                        + expectedBytes + ", got " + bytesWritten);
            }
        }
    }
}
