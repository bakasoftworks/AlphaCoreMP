package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;

/**
 * Client-side decoder for the server's {@code DIVE} phase-dive-start wire frame:
 * the receiving half of the project's
 * {@code cc.starsector.server.runtime.PhaseDiveEventCodec}.
 *
 * <p>The {@link ClientDespawnEventReader} idiom minus the reason field — a lean
 * fixed-width frame (magic / wire-type-code / schema / entityId / simTick, no strings,
 * no wire-derived lengths) read over plain {@link InputStream} primitives (the
 * mod-script sandbox blocks {@code DataInputStream} — see {@link WireBytes}), so the
 * bytes stay byte-identical to the server frame.
 *
 * <p>Fail-closed: a wrong magic (when read standalone), an unexpected schema version,
 * or invalid fields throw {@link IOException}; a clean end-of-stream at a frame
 * boundary returns {@code null} from {@link #readEvent(InputStream)}.
 */
public final class ClientPhaseDiveReader {

    /** Frame magic ("DIVE"). Mirrors {@code PhaseDiveEventCodec.MAGIC}. */
    public static final int MAGIC = 0x44495645;

    /** Schema version this reader understands. Mirrors {@code ServerPhaseDiveEventMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** magic(4) + wireTypeCode(4) + schema(2) + entityId(8) + simTick(8). */
    public static final int FRAME_BYTES = 4 + 4 + 2 + 8 + 8;

    private ClientPhaseDiveReader() {
    }

    /**
     * Decode one phase-dive-start frame from {@code in}, or return {@code null} at a
     * clean end-of-stream (a frame boundary). Throws on a corrupt/truncated frame.
     */
    public static ClientPhaseDive readEvent(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("phase-dive event frame magic mismatch: " + Integer.toHexString(magic));
        }
        return readEventAfterMagic(in);
    }

    /**
     * Decode one phase-dive-start frame whose 4-byte {@link #MAGIC} has <em>already</em>
     * been read and validated by the caller — the
     * {@link MultiplexedServerStreamReader} demux entry point.
     */
    public static ClientPhaseDive readEventAfterMagic(InputStream in) throws IOException {
        WireBytes.readInt(in); // wire type code — tolerated (the client carries no registry)
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("phase-dive event schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        long entityId = WireBytes.readLong(in);
        long simTick = WireBytes.readLong(in);
        try {
            return new ClientPhaseDive(entityId, simTick);
        } catch (IllegalArgumentException badEvent) {
            throw new IOException("phase-dive event fields invalid: " + badEvent.getMessage(), badEvent);
        }
    }
}
