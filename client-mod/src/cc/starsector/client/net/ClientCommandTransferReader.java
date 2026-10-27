package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code XFER} command-transfer control wire frame:
 * the receiving half of the project's
 * {@code cc.starsector.server.runtime.CommandTransferFrameCodec}.
 *
 * <p>The server fans one {@code XFER} frame per transfer edge (begin/deny/commit/abort) and
 * replays each in-flight row as BEGIN to late joiners AFTER the RAPP catch-up (the deliberate
 * MMNT-ordering inversion — a replayed BEGIN references roster ids RAPP just taught). The
 * {@link MultiplexedServerStreamReader} routes the frame here by its leading magic; there is
 * deliberately no standalone-magic entry point (the demux is the only caller).
 *
 * <p>Like {@link ClientModuleMountReader}, the mod deliberately does not bundle the server
 * codec (which uses {@code java.io.DataInputStream} — blocked by the mod-script sandbox
 * classloader, see {@link WireBytes}). It reads exactly the bytes the server writes — magic /
 * schema / headerBytes / wireTypeCode / bodyLen / body / CRC32, body v1 FIXED 34 B, no UTF =
 * {@code u64 seatWireId | u64 fromWireId | u64 toWireId | u64 podWireId | u8 kind | u8 reason}
 * — over plain {@link InputStream} primitives plus the allowlisted {@link CRC32}.
 *
 * <p>Fail-closed: an unexpected schema version, a header size mismatch, ANY declared body
 * length other than 34, a CRC32 mismatch, or an out-of-law transfer (bad kind, a reason
 * outside the deny/abort vocabulary, a reason on begin/commit, non-positive seat/from ids, a
 * begin/commit without a target, negative to/pod ids) all throw {@link IOException}.
 */
public final class ClientCommandTransferReader {

    /** Frame magic: ASCII "XFER". Mirrors {@code CommandTransferFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x58464552;

    /** Mirrors {@code CommandTransferControlMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** Header bytes: magic(4) + schema(2) + headerBytes(2) + wireTypeCode(4) + bodyBytes(4). */
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4;

    /** The FIXED body: seat(8) + from(8) + to(8) + pod(8) + kind(1) + reason(1). */
    public static final int BODY_BYTES = 34;

    private ClientCommandTransferReader() {
    }

    /**
     * Decode one command-transfer frame whose 4-byte {@link #MAGIC} has <em>already</em> been
     * read and validated by the caller — the {@link MultiplexedServerStreamReader} entry point.
     */
    public static ClientCommandTransfer readTransferAfterMagic(InputStream in)
            throws IOException {
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("command-transfer frame schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("command-transfer frame header size mismatch: " + headerBytes);
        }
        WireBytes.readInt(in); // wire type code — tolerated (the client carries no registry)
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes != BODY_BYTES) {
            throw new IOException("command-transfer frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("command-transfer frame checksum mismatch");
        }
        long seatWireId = WireBytes.getLong(body, 0);
        long fromWireId = WireBytes.getLong(body, 8);
        long toWireId = WireBytes.getLong(body, 16);
        long podWireId = WireBytes.getLong(body, 24);
        int kind = body[32] & 0xff;
        int reason = body[33] & 0xff;
        try {
            return new ClientCommandTransfer(seatWireId, fromWireId, toWireId, podWireId,
                    kind, reason);
        } catch (IllegalArgumentException badFrame) {
            throw new IOException("command-transfer frame invalid", badFrame);
        }
    }
}
