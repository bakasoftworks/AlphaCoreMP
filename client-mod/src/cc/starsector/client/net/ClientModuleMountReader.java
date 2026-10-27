package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code MMNT} module-mount handover control wire frame
 * (W2b module piloting): the receiving half of the project's
 * {@code cc.starsector.server.runtime.ModuleMountFrameCodec}.
 *
 * <p>The server fans one {@code MMNT} frame per seat mount/dismount handover (fanned BEFORE
 * the paired {@code RAPP} row) and replays each live mount (kind 1, {@code moduleOldWireId=0}
 * sentinel) to late joiners BEFORE the RAPP catch-up, so the mounted-seat guard is warm when
 * the seat's roster row arrives. The {@link MultiplexedServerStreamReader} routes the frame
 * here by its leading magic.
 *
 * <p>Like {@link ClientRosterAppendReader}, the mod deliberately does not bundle the server
 * codec (which uses {@code java.io.DataInputStream} — blocked by the mod-script sandbox
 * classloader, see {@link WireBytes}). It reads exactly the bytes the server writes — magic /
 * schema / headerBytes / wireTypeCode / bodyLen / body / CRC32, body v1 =
 * {@code u64 seatWireId | u64 moduleOldWireId | u8 kind | UTF slotToken} — over plain
 * {@link InputStream} primitives plus the allowlisted {@link CRC32}. The UTF string is
 * {@code DataOutputStream.writeUTF} modified UTF-8, decoded with the same accept/reject rules
 * as {@code DataInputStream.readUTF}.
 *
 * <p>Fail-closed: a wrong magic (when read standalone), an unexpected schema version, a
 * header/body size mismatch, a CRC32 mismatch, a malformed UTF sequence, an out-of-law
 * handover (bad kind, non-positive seat id, blank/over-long/non-printable token, a dismount
 * without a fresh id), or trailing body bytes all throw {@link IOException}; a clean
 * end-of-stream at a frame boundary returns {@code null} from {@link #readMount(InputStream)}.
 */
public final class ClientModuleMountReader {

    /** Frame magic: ASCII "MMNT". Mirrors {@code ModuleMountFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x4D4D4E54;

    /** Schema version this reader understands. Mirrors {@code ModuleMountControlMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** Header bytes: magic(4) + schema(2) + headerBytes(2) + wireTypeCode(4) + bodyBytes(4). */
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4;

    /** The whole-frame budget. Mirrors {@code ModuleMountFrameCodec.MAX_FRAME_BYTES}. */
    public static final int MAX_FRAME_BYTES = 1164;

    /** The variable body's size bound implied by the frame budget (frame − header − CRC32). */
    public static final int MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES - 4;

    /** Fixed body prefix: seatWireId(8) + moduleOldWireId(8) + kind(1). */
    private static final int FIXED_BODY_BYTES = 8 + 8 + 1;

    private ClientModuleMountReader() {
    }

    /**
     * Decode one module-mount frame from {@code in}, or return {@code null} at a clean
     * end-of-stream (a frame boundary). Throws on a corrupt/truncated frame.
     */
    public static ClientModuleMount readMount(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("module-mount frame magic mismatch: " + Integer.toHexString(magic));
        }
        return readMountAfterMagic(in);
    }

    /**
     * Decode one module-mount frame whose 4-byte {@link #MAGIC} has <em>already</em> been read
     * and validated by the caller — the {@link MultiplexedServerStreamReader} entry point.
     */
    public static ClientModuleMount readMountAfterMagic(InputStream in) throws IOException {
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("module-mount frame schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("module-mount frame header size mismatch: " + headerBytes);
        }
        WireBytes.readInt(in); // wire type code — tolerated (the client carries no registry)
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes < 1 || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("module-mount frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("module-mount frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static ClientModuleMount decodeBody(byte[] body) throws IOException {
        if (body.length < FIXED_BODY_BYTES + 2) {
            throw new IOException("module-mount frame body truncated: " + body.length);
        }
        long seatWireId = WireBytes.getLong(body, 0);
        long moduleOldWireId = WireBytes.getLong(body, 8);
        int kind = body[16] & 0xff;
        int[] cursor = {FIXED_BODY_BYTES};
        String slotToken = readUtf(body, cursor);
        if (cursor[0] != body.length) {
            throw new IOException("module-mount frame body has trailing bytes");
        }
        try {
            return new ClientModuleMount(seatWireId, moduleOldWireId, kind, slotToken);
        } catch (IllegalArgumentException badFrame) {
            throw new IOException("module-mount frame invalid", badFrame);
        }
    }

    /**
     * Decode one {@code DataOutputStream.writeUTF} string at {@code cursor[0]} — u16 byte
     * length + modified UTF-8 (1/2/3-byte chars, {@code DataInputStream.readUTF} semantics) —
     * advancing the cursor past it. Malformed or truncated sequences throw {@link IOException}.
     */
    private static String readUtf(byte[] body, int[] cursor) throws IOException {
        int off = cursor[0];
        if (off + 2 > body.length) {
            throw new IOException("module-mount slotToken length truncated");
        }
        int len = ((body[off] & 0xff) << 8) | (body[off + 1] & 0xff);
        off += 2;
        int end = off + len;
        if (end > body.length) {
            throw new IOException("module-mount slotToken bytes truncated: " + len);
        }
        StringBuilder chars = new StringBuilder(len);
        while (off < end) {
            int a = body[off] & 0xff;
            if ((a & 0x80) == 0) {
                chars.append((char) a);
                off += 1;
            } else if ((a >> 5) == 0x6) {
                if (off + 1 >= end) {
                    throw new IOException("module-mount slotToken UTF sequence truncated");
                }
                int b = body[off + 1] & 0xff;
                if ((b & 0xC0) != 0x80) {
                    throw new IOException("module-mount slotToken UTF sequence malformed");
                }
                chars.append((char) (((a & 0x1F) << 6) | (b & 0x3F)));
                off += 2;
            } else if ((a >> 4) == 0xE) {
                if (off + 2 >= end) {
                    throw new IOException("module-mount slotToken UTF sequence truncated");
                }
                int b = body[off + 1] & 0xff;
                int c = body[off + 2] & 0xff;
                if ((b & 0xC0) != 0x80 || (c & 0xC0) != 0x80) {
                    throw new IOException("module-mount slotToken UTF sequence malformed");
                }
                chars.append((char) (((a & 0x0F) << 12) | ((b & 0x3F) << 6) | (c & 0x3F)));
                off += 3;
            } else {
                throw new IOException("module-mount slotToken UTF sequence malformed");
            }
        }
        cursor[0] = end;
        return chars.toString();
    }
}
