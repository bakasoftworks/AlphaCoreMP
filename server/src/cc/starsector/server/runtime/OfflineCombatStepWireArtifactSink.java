package cc.starsector.server.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded byte-emitting sink for completed offline combat step results.
 */
public final class OfflineCombatStepWireArtifactSink implements OfflineCombatStepSink, AutoCloseable {

    private final Path artifactPath;
    private final int maxFrames;
    private final OutputStream output;
    private final List<OfflineCombatStepWireFrame> frames = new ArrayList<>();
    private boolean closed;

    private OfflineCombatStepWireArtifactSink(Path artifactPath, int maxFrames, OutputStream output) {
        this.artifactPath = artifactPath;
        this.maxFrames = maxFrames;
        this.output = output;
    }

    public static OfflineCombatStepWireArtifactSink open(Path artifactPath, int maxFrames)
            throws IOException {
        Objects.requireNonNull(artifactPath, "artifactPath");
        if (maxFrames < 0) {
            throw new IllegalArgumentException("maxFrames must be >= 0; got " + maxFrames);
        }
        Path parent = artifactPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        OutputStream output = Files.newOutputStream(
                artifactPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return new OfflineCombatStepWireArtifactSink(artifactPath, maxFrames, output);
    }

    @Override
    public void publish(OfflineCombatStepResult result) {
        Objects.requireNonNull(result, "result");
        if (closed) {
            throw new IllegalStateException("offline step wire artifact sink is closed");
        }
        if (frames.size() >= maxFrames) {
            throw new IllegalStateException("offline step wire artifact frame bound exceeded");
        }
        try {
            frames.add(OfflineCombatStepWireCodec.writeFrame(output, frames.size(), result));
        } catch (IOException ioe) {
            throw new UncheckedIOException("failed to write offline step wire frame", ioe);
        }
    }

    public Path artifactPath() {
        return artifactPath;
    }

    public int maxFrames() {
        return maxFrames;
    }

    public int frameCount() {
        return frames.size();
    }

    public List<OfflineCombatStepWireFrame> frames() {
        return List.copyOf(frames);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            output.close();
        }
    }
}
