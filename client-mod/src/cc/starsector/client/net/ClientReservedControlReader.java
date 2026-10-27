package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side TOLERANT decoder for the two RESERVED control kinds frozen:
 * {@code DESC} descriptor-on-CTRL
 * carriage and {@code HOUS} house/operator signals. Mirrors the server's
 * {@code cc.starsector.server.runtime.ReservedControlFrameCodec} envelope byte-exactly —
 * {@code magic | schema u16 | headerBytes u16 (=16) | wireTypeCode u32 | bodyBytes u32 |
 * body | CRC32(body)} — over the sandbox-safe {@link InputStream} primitives
 * ({@link WireBytes}).
 *
 * <p><b>Deliberately NOT fail-closed on schema</b> — the one seam where this reader differs
 * from every sibling ({@code ClientMatchResetReader} etc., charter R5 fail-closed): the
 * whole point of the reservation is that a kit minted TODAY skips a frame whose body is
 * defined YEARS from now, at any future schema, without a ×N re-kit. So: schema and wire
 * type code are read and carried but never validated. Everything that protects stream sync
 * stays loud: header length must be 16 (charter R1 — header evolution is illegal), the
 * declared body length is sanity-bounded before allocation, the body CRC32 must verify
 * (corruption is not tolerance), and a magic outside the two reserved kinds is still the
 * demux's fail-closed business.
 */
public final class ClientReservedControlReader {

    /** Frame magic: ASCII "DESC". Mirrors {@code ReservedControlFrameCodec.MAGIC_DESCRIPTOR}. */
    public static final int MAGIC_DESCRIPTOR = 0x44455343;

    /** Frame magic: ASCII "HOUS". Mirrors {@code ReservedControlFrameCodec.MAGIC_HOUSE}. */
    public static final int MAGIC_HOUSE = 0x484F5553;

    /** Header bytes: magic(4) + schema(2) + headerBytes(2) + wireTypeCode(4) + bodyBytes(4). */
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4;

    /** Sanity bound on a declared body length. Mirrors {@code ReservedControlFrameCodec}. */
    public static final int MAX_BODY_BYTES = 1 << 20;

    /** HOUS body byte 0 (the frozen role position) = 1: KICKED notice — the reservation's
     *  FIRST consumer. Mirrors {@code HouseControlMessage.ROLE_KICKED_NOTICE}. */
    public static final int HOUSE_ROLE_KICKED_NOTICE = 1;
    /** Kicked reason 1: the in-match admin {@code /kick} verb. Mirrors the server constant. */
    public static final int KICKED_REASON_ADMIN_KICK = 1;
    /** Kicked reason 2: the ops-GUI / operator-file kick. Mirrors the server constant. */
    public static final int KICKED_REASON_OPERATOR_KICK = 2;
    /** Kicked reason 3: the door released this one-way pending seat
     *  to another joiner (this client never began exchanging). Mirrors the server constant. */
    public static final int KICKED_REASON_PENDING_EVICTED = 3;
    /** Kicked reason 4: the operator BANNED this client — terminal,
     *  the door refuses its rejoin with {@code banned}. Mirrors {@code
     *  HouseControlMessage.KICKED_REASON_BANNED}. */
    public static final int KICKED_REASON_BANNED = 4;

    private ClientReservedControlReader() {
    }

    /**
     * Parse a tolerated reserved frame as a KICKED notice — returns the u8
     * reason code, or {@code -1} when the frame is not one (DESC, another HOUS role, empty
     * body). Tolerant like everything on this reader: a role-1 body too short for the reason
     * byte reads as reason 0 (unknown), trailing/future body bytes are ignored, and the
     * schema stays unvalidated — a future body v2 must still parse-or-ignore cleanly here.
     */
    public static int kickedNoticeReasonOrMinusOne(ClientReservedControl frame) {
        if (frame == null || frame.magic() != MAGIC_HOUSE || frame.bodyLength() < 1) {
            return -1;
        }
        byte[] body = frame.body();
        if (body[0] != HOUSE_ROLE_KICKED_NOTICE) {
            return -1;
        }
        return body.length >= 2 ? body[1] & 0xFF : 0;
    }

    /** True when {@code magic} is one of the two reserved control kinds. */
    public static boolean isReservedMagic(int magic) {
        return magic == MAGIC_DESCRIPTOR || magic == MAGIC_HOUSE;
    }

    /**
     * Decode one reserved control frame whose 4-byte magic has <em>already</em> been read and
     * matched by the demultiplexer ({@link MultiplexedServerStreamReader}). Tolerant of any
     * schema / wire type code; loud on anything that would break stream sync (header size,
     * insane body length, CRC).
     */
    public static ClientReservedControl readAfterMagic(int magic, InputStream in)
            throws IOException {
        if (!isReservedMagic(magic)) {
            throw new IOException("not a reserved control magic: " + Integer.toHexString(magic));
        }
        int schema = WireBytes.readUnsignedShort(in); // tolerated — never validated (see class doc)
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("reserved control frame header size mismatch: " + headerBytes
                    + " (header evolution is illegal — charter R1; the length-skip contract"
                    + " rides a fixed 16-byte header)");
        }
        int wireTypeCode = WireBytes.readInt(in); // tolerated (the client carries no registry)
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes < 0 || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("reserved control frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("reserved control frame checksum mismatch");
        }
        return new ClientReservedControl(magic, schema, wireTypeCode, body);
    }
}
