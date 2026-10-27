package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;

/**
 * Client-side decoder for the server's {@code EDSP} entity-despawn-event wire frame
 * (the relevance-safe despawn lane): the receiving half of the project's
 * {@code cc.starsector.server.runtime.EntityDespawnEventCodec}.
 *
 * <p>This is the durable, full-roster despawn signal the
 * {@link RosterLifecycleTracker} roster-diff path cannot safely produce. Roster diff
 * over the snapshot stream is only correct under a full-roster broadcast; once
 * per-peer relevance scoping drops an entity from a peer's frame, the diff cannot
 * tell "left the world" from "left my interest set" (see {@link ClientEntityDespawn}
 * for the diff path). The server therefore also emits an explicit despawn event
 * derived from its <em>full</em> authoritative roster
 * ({@code EntityDespawnEventDetector}); this reader decodes that event on the client.
 *
 * <p>Like {@link AwssSnapshotReader}, the mod deliberately does not bundle the
 * server codec (which uses {@code java.io.DataInputStream} — a type the Starsector
 * mod-script sandbox classloader blocks, see {@link WireBytes}). It instead reads
 * exactly the fixed-width bytes the server writes — magic / wire-type-code / schema /
 * entityId / simTick / reasonCode — over plain {@link InputStream} primitives, so the
 * bytes stay byte-identical to the server frame. The game-free cross-check
 * script streams real server
 * {@code EntityDespawnEventCodec} bytes over a loopback socket and asserts this reader
 * decodes the same events, keeping the two mirrors in sync.
 *
 * <p>Fail-closed: a wrong magic (when read standalone), an unexpected schema version,
 * or an unknown reason code throws {@link IOException}; a clean end-of-stream at a
 * frame boundary returns {@code null} from {@link #readEvent(InputStream)}.
 */
public final class ClientDespawnEventReader {

    /** Frame magic ("EDSP"). Mirrors {@code EntityDespawnEventCodec.MAGIC}. */
    public static final int MAGIC = 0x45445350;

    /** Schema version this reader understands. Mirrors {@code EntityDespawnEventCodec.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** magic(4) + wireTypeCode(4) + schema(2) + entityId(8) + simTick(8) + reasonCode(4). */
    public static final int FRAME_BYTES = 4 + 4 + 2 + 8 + 8 + 4;

    private ClientDespawnEventReader() {
    }

    /**
     * Decode one despawn-event frame from {@code in}, or return {@code null} at a
     * clean end-of-stream (a frame boundary). Throws on a corrupt/truncated frame.
     */
    public static ClientEntityDespawn readEvent(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("despawn event frame magic mismatch: " + Integer.toHexString(magic));
        }
        return readEventAfterMagic(in);
    }

    /**
     * Decode one despawn-event frame whose 4-byte {@link #MAGIC} has <em>already</em>
     * been read and validated by the caller. This is the entry point the multiplexed
     * stream demultiplexer ({@link MultiplexedServerStreamReader}) uses after it peeks
     * the leading magic and finds this is an {@code EDSP} frame rather than an
     * {@code AWSS} world snapshot.
     */
    public static ClientEntityDespawn readEventAfterMagic(InputStream in) throws IOException {
        WireBytes.readInt(in); // wire type code — tolerated (the client carries no registry)
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("despawn event schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        long entityId = WireBytes.readLong(in);
        long simTick = WireBytes.readLong(in);
        int reasonCode = WireBytes.readInt(in);
        ClientDespawnReason reason;
        try {
            reason = ClientDespawnReason.fromWireCode(reasonCode);
        } catch (IllegalArgumentException badReason) {
            throw new IOException("despawn event reason code invalid: " + reasonCode, badReason);
        }
        return new ClientEntityDespawn(entityId, simTick, reason);
    }
}
