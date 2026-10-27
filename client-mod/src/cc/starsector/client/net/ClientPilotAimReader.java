package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code AIMP} batched pilot-aim control wire frame
 * (remote turret aim): the receiving half of the project's
 * {@code cc.starsector.server.runtime.PilotAimFrameCodec}.
 *
 * <p>The server fans ONE shared change-gated frame per due tick (cadence
 * {@code cc.server.aimFanEveryTicks} ≈8 Hz, keepalive ≈1 s, armed-door-gated) carrying every
 * piloted seat's live aim WORLD point on a fixed-point 1/8-world-unit grid. The client drops
 * its OWN row (selection-not-minting) and slews remote piloted ships' manual turret
 * groups toward the rest via the engine-native mouseTarget seam
 * ({@code cc.starsector.client.RemoteAimLane}). The {@link MultiplexedServerStreamReader}
 * routes the frame here by its leading magic.
 *
 * <p>Like {@link ClientModuleMountReader}, the mod deliberately does not bundle the server
 * codec (which uses {@code java.io.DataInputStream} — blocked by the mod-script sandbox
 * classloader, see {@link WireBytes}). It reads exactly the bytes the server writes — magic /
 * schema / headerBytes / wireTypeCode / bodyLen / body / CRC32, body v1 fixed-width, NO UTF:
 * {@code u8 rowCount (1..64)} then per row {@code u64 shipWireId | i32 qx | i32 qy} — over
 * plain {@link InputStream} primitives plus the allowlisted {@link CRC32}.
 *
 * <p>Fail-closed: a wrong magic (when read standalone), an unexpected schema version, a
 * header/body size mismatch (the declared body size must equal the fixed-width row arithmetic
 * exactly), a CRC32 mismatch, or an out-of-law row (non-positive ship id, implausible |q|) all
 * throw {@link IOException}; a clean end-of-stream at a frame boundary returns {@code null}
 * from {@link #readAims(InputStream)}.
 */
public final class ClientPilotAimReader {

    /** Frame magic: ASCII "AIMP". Mirrors {@code PilotAimFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x41494D50;

    /** Schema version this reader understands. Mirrors {@code PilotAimControlMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** Header bytes: magic(4) + schema(2) + headerBytes(2) + wireTypeCode(4) + bodyBytes(4). */
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4;

    /** Hard row cap per frame (u8 count). Mirrors {@code PilotAimFrameCodec.MAX_ROWS}. */
    public static final int MAX_ROWS = 64;

    /** Fixed-point aim grid steps per world unit. Mirrors {@code PilotAimFrameCodec.GRID_STEPS_PER_UNIT}. */
    public static final int GRID_STEPS_PER_UNIT = 8;

    /** Plausibility cap on |qx|/|qy| (±100000 u × grid). Mirrors {@code PilotAimFrameCodec.MAX_QUANT_ABS}. */
    public static final int MAX_QUANT_ABS = 800000;

    /** Bytes per row: shipWireId(8) + qx(4) + qy(4). */
    private static final int ROW_BYTES = 8 + 4 + 4;

    /** The fixed-width body bound implied by the row cap (64 rows = 1025 B). */
    private static final int MAX_BODY_BYTES = 1 + MAX_ROWS * ROW_BYTES;

    private ClientPilotAimReader() {
    }

    /**
     * Decode one pilot-aim frame from {@code in}, or return {@code null} at a clean
     * end-of-stream (a frame boundary). Throws on a corrupt/truncated frame.
     */
    public static ClientPilotAimFrame readAims(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("pilot-aim frame magic mismatch: " + Integer.toHexString(magic));
        }
        return readAimsAfterMagic(in);
    }

    /**
     * Decode one pilot-aim frame whose 4-byte {@link #MAGIC} has <em>already</em> been read
     * and validated by the caller — the {@link MultiplexedServerStreamReader} entry point.
     */
    public static ClientPilotAimFrame readAimsAfterMagic(InputStream in) throws IOException {
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("pilot-aim frame schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("pilot-aim frame header size mismatch: " + headerBytes);
        }
        WireBytes.readInt(in); // wire type code — tolerated (the client carries no registry)
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes < 1 + ROW_BYTES || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("pilot-aim frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("pilot-aim frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static ClientPilotAimFrame decodeBody(byte[] body) throws IOException {
        int rowCount = body[0] & 0xff;
        if (rowCount < 1 || rowCount > MAX_ROWS) {
            throw new IOException("pilot-aim frame row count invalid: " + rowCount);
        }
        if (body.length != 1 + rowCount * ROW_BYTES) {
            throw new IOException("pilot-aim frame body length " + body.length
                    + " does not match rowCount " + rowCount);
        }
        long[] shipWireIds = new long[rowCount];
        int[] qx = new int[rowCount];
        int[] qy = new int[rowCount];
        for (int i = 0; i < rowCount; i++) {
            int off = 1 + i * ROW_BYTES;
            shipWireIds[i] = WireBytes.getLong(body, off);
            qx[i] = WireBytes.getInt(body, off + 8);
            qy[i] = WireBytes.getInt(body, off + 12);
        }
        try {
            return new ClientPilotAimFrame(shipWireIds, qx, qy);
        } catch (IllegalArgumentException badRow) {
            throw new IOException("pilot-aim frame row invalid", badRow);
        }
    }
}
