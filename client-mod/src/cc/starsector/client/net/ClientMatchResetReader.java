package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code MRST} match-reset control wire frame
 * (L1 session-lifecycle /): the receiving half of the project's
 * {@code cc.starsector.server.runtime.MatchResetFrameCodec}.
 *
 * <p>This is the explicit next-round boundary the predict client decodes to tear down its
 * local prediction combat and rebuild for the fresh round at exactly the right point in the
 * stream — the boundary the read-only reset lane left only IMPLICIT in the entity-count
 * sequence, which a client running its own engine cannot infer. The server
 * interleaves this {@code MRST} frame inline on the SAME persistent socket as the {@code AWSS}
 * snapshot stream between two rounds; the
 * {@link MultiplexedServerStreamReader} routes it here by its leading magic.
 *
 * <p>Like {@link AwssSnapshotReader} / {@link ClientDespawnEventReader}, the mod deliberately
 * does not bundle the server codec (which uses {@code java.io.DataInputStream} — a type the
 * Starsector mod-script sandbox classloader blocks, see {@link WireBytes}). It instead reads
 * exactly the fixed-width bytes the server writes — magic / schema / headerBytes / wireTypeCode
 * / bodyBytes / body / CRC32 — over plain {@link InputStream} primitives plus the allowlisted
 * {@link CRC32}, so the bytes stay byte-identical to the server frame. The game-free cross-check
 * streams real server
 * {@code MatchResetFrameCodec} bytes (interleaved with real {@code AWSS} frames) over a loopback
 * socket and asserts this reader decodes the same signal, keeping the two mirrors in sync.
 *
 * <p>Fail-closed: a wrong magic (when read standalone), an unexpected schema version, a header /
 * body size mismatch, a CRC32 mismatch, or out-of-range fields all throw {@link IOException}; a
 * clean end-of-stream at a frame boundary returns {@code null} from {@link #readReset(InputStream)}.
 */
public final class ClientMatchResetReader {

    /** Frame magic: ASCII "MRST". Mirrors {@code MatchResetFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x4D525354;

    /** Schema version this reader understands. Mirrors {@code MatchResetControlMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** Header bytes: magic(4) + schema(2) + headerBytes(2) + wireTypeCode(4) + bodyBytes(4). */
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4;

    /** Body bytes: resetSeq(4) + completedRoundIndex(4) + nextRoundIndex(4). */
    public static final int BODY_BYTES = 4 + 4 + 4;

    private ClientMatchResetReader() {
    }

    /**
     * Decode one match-reset frame from {@code in}, or return {@code null} at a clean
     * end-of-stream (a frame boundary). Throws on a corrupt/truncated frame.
     */
    public static ClientMatchReset readReset(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("match-reset frame magic mismatch: " + Integer.toHexString(magic));
        }
        return readResetAfterMagic(in);
    }

    /**
     * Decode one match-reset frame whose 4-byte {@link #MAGIC} has <em>already</em> been read
     * and validated by the caller. This is the entry point the multiplexed-stream demultiplexer
     * ({@link MultiplexedServerStreamReader}) uses after it peeks the leading magic and finds this
     * is an {@code MRST} frame rather than an {@code AWSS} world snapshot.
     */
    public static ClientMatchReset readResetAfterMagic(InputStream in) throws IOException {
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("match-reset frame schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("match-reset frame header size mismatch: " + headerBytes);
        }
        WireBytes.readInt(in); // wire type code — tolerated (the client carries no registry)
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes != BODY_BYTES) {
            throw new IOException("match-reset frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("match-reset frame checksum mismatch");
        }
        int resetSeq = WireBytes.getInt(body, 0);
        int completedRoundIndex = WireBytes.getInt(body, 4);
        int nextRoundIndex = WireBytes.getInt(body, 8);
        try {
            return new ClientMatchReset(resetSeq, completedRoundIndex, nextRoundIndex);
        } catch (IllegalArgumentException badRecord) {
            throw new IOException("match-reset frame body invalid: " + badRecord.getMessage(), badRecord);
        }
    }
}
