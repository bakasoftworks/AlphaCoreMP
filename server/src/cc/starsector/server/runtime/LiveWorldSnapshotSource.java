package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import java.io.IOException;

/**
 * A per-tick source of authoritative world snapshots for the live publish loop
 * ({@link LiveWorldSnapshotSocketPublisher}).
 *
 * <p>Each {@link #publishNextTick()} call advances the simulation exactly one tick
 * and returns the {@link AuthoritativeWorldSnapshot} for that completed tick. The
 * publisher pulls one snapshot per tick and writes it to the socket immediately,
 * so the bytes that cross the wire are produced frame-by-frame as the world
 * evolves — not pre-encoded into an artifact ahead of time and
 * not accumulated into a list and flushed post-loop.
 *
 * <p>The engine realization is
 * {@link cc.starsector.server.bridge.EngineBackedLiveWorldSnapshotSource}, which
 * steps the real combat engine and reads the live per-ship views; the
 * game-free contract test supplies a deterministic in-memory source. Either way the
 * publisher and the separate-JVM receiver are identical.
 */
public interface LiveWorldSnapshotSource {

    /** Total ticks this source will publish; the publisher streams exactly this many frames. */
    int expectedFrames();

    /**
     * Advance the simulation one tick and return the authoritative snapshot for the
     * completed tick. Implementations fail closed (throwing) rather than returning a
     * partial or stale snapshot when the underlying world cannot be read.
     */
    AuthoritativeWorldSnapshot publishNextTick() throws IOException;
}
