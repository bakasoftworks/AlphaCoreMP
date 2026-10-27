package cc.starsector.server.runtime;

import java.io.IOException;
import java.util.zip.CRC32;

/**
 * The datagram wire envelope: every
 * UDP datagram is one 32-byte big-endian header + exactly one payload frame (an EXISTING codec's
 * bytes, unchanged — the datagram supplies the frame boundary) + a 4-byte CRC32 tail over
 * header+payload (UDP's own checksum is optional/weak, and the CRC covers the header on purpose).
 *
 * <p><b>Posture split:</b> {@link #decode} answers an internet-exposed UDP port that
 * eats spoofable bytes — truncation, a datagram past {@value #MAX_DATAGRAM_BYTES} B, bad
 * magic/version/CRC, or an unknown channel byte return {@code null} (SILENT drop; the caller counts, never throws on unauthenticated input). Session-id
 * validation is the endpoint's job (the codec has no session table). {@link #encode} runs
 * post-admission on OUR OWN state, so it THROWS loud on any violation: an over-MTU datagram
 * (payload + {@value #FRAMING_BYTES} &gt; {@value #MAX_DATAGRAM_BYTES}, the guard on the
 * DATAGRAM, not the payload — no fragmentation v1), or a field outside its wire width.
 *
 * <p><b>Unsigned 4-byte fields:</b>
 * {@code seq}/{@code ctrlAck}/{@code snapHiSeen} are unsigned 32-bit on the wire and long-domain in
 * {@link DatagramReliability} — decode widens them unsigned; sign-extension would corrupt compare
 * order past bit 31. {@code ctrlAckBits} is a 32-bit BITFIELD (no arithmetic order), carried as the
 * int it is. Seq spaces are monotone-from-1 and never reset, so the unsigned-32 ceiling
 * (~99 days of 500 Hz SNAP) is unreachable within any serve; encode still range-checks loud.
 */
public final class DatagramFrameCodec {

    /** Wire channel byte. {@link DatagramReliability.Channel} covers the data three;
     * HELLO/WELCOME (admission) and BYE (goodbye) exist only at the envelope layer.
     * VOICE (sub-frames {@link VoiceFrameCodec}) is unreliable like SNAP
     * and never touches {@link DatagramReliability}: seq = the voice sequence, ctrlAck /
     * ctrlAckBits / snapHiSeen stamped 0. Pre-VOICE peers see channel 6 as unknown ⇒ silent
     * drop (backward-safe by the existing posture). VCHK (payload
     * {@link VariantCheckFrameCodec}) is the PRE-ADMISSION custom-variant dry-run: like HELLO it
     * is dispatched before any session lookup (sessionId 0 up; the reply echoes the request
     * nonce AS the sessionId, seq = the submitSeq) and never touches {@link DatagramReliability};
     * a live session drops it as a bad session, pre-VCHK peers as unknown. */
    public enum WireChannel {
        HELLO(0), WELCOME(1), SNAP(2), CTRL(3), ACK(4), BYE(5), VOICE(6), VCHK(7);

        public final int wire;

        WireChannel(int wire) {
            this.wire = wire;
        }

        static WireChannel fromWireOrNull(int wire) {
            return switch (wire) {
                case 0 -> HELLO;
                case 1 -> WELCOME;
                case 2 -> SNAP;
                case 3 -> CTRL;
                case 4 -> ACK;
                case 5 -> BYE;
                case 6 -> VOICE;
                case 7 -> VCHK;
                default -> null; // unknown channel = spoofable input, silent-drop posture
            };
        }
    }

    public static final int MAGIC = 0xCCDA;
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 32;
    public static final int CRC_BYTES = 4;
    /** Header + CRC tail — what framing adds to every payload. */
    public static final int FRAMING_BYTES = HEADER_BYTES + CRC_BYTES;
    /** Conservative MTU floor (1500 minus VPN/tunnel overhead) — bounds the DATAGRAM both
     * ways: {@link #encode} throws past it, {@link #decode} returns {@code null} past it. */
    public static final int MAX_DATAGRAM_BYTES = 1200;
    /** The largest payload one datagram may carry; a bigger frame THROWS (no fragmentation v1). */
    public static final int MAX_PAYLOAD_BYTES = MAX_DATAGRAM_BYTES - FRAMING_BYTES;

    private static final long UNSIGNED_32_MAX = 0xFFFF_FFFFL;

    /** One decoded datagram; {@code payload} is zero-length for header-only frames (bare ACKs). */
    public record Frame(WireChannel channel, int roundId, long sessionId, long seq, long ctrlAck,
            int ctrlAckBits, long snapHiSeen, byte[] payload) {
    }

    private DatagramFrameCodec() {
    }

    /**
     * Serialize one datagram. {@code payload} null = header-only (a bare ACK/keepalive/BYE).
     * Post-admission state, so every violation THROWS
     * (house rule — never a silent truncation).
     */
    public static byte[] encode(WireChannel channel, int roundId, long sessionId, long seq,
            long ctrlAck, int ctrlAckBits, long snapHiSeen, byte[] payload) {
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
        if (roundId < 0 || roundId > 0xFF) {
            throw new IllegalArgumentException("roundId must fit an unsigned byte: " + roundId);
        }
        requireUnsigned32("seq", seq);
        requireUnsigned32("ctrlAck", ctrlAck);
        requireUnsigned32("snapHiSeen", snapHiSeen);
        int payloadLen = payload == null ? 0 : payload.length;
        if (payloadLen > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("datagram over the " + MAX_DATAGRAM_BYTES
                    + " B MTU floor: payload " + payloadLen + " B + framing " + FRAMING_BYTES
                    + " B (max payload " + MAX_PAYLOAD_BYTES + " B; no fragmentation v1,"
                    + " by design)");
        }
        byte[] out = new byte[FRAMING_BYTES + payloadLen];
        putU16(out, 0, MAGIC);
        out[2] = (byte) VERSION;
        out[3] = (byte) channel.wire;
        out[4] = (byte) roundId;
        // 5..7 reserved = 0
        putU64(out, 8, sessionId);
        putU32(out, 16, seq);
        putU32(out, 20, ctrlAck);
        putU32(out, 24, ctrlAckBits & UNSIGNED_32_MAX);
        putU32(out, 28, snapHiSeen);
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, out, HEADER_BYTES, payloadLen);
        }
        CRC32 crc = new CRC32();
        crc.update(out, 0, HEADER_BYTES + payloadLen);
        putU32(out, HEADER_BYTES + payloadLen, crc.getValue());
        return out;
    }

    /**
     * Parse one received datagram ({@code data[0..length)}, the {@code DatagramPacket} shape).
     * Returns {@code null} on ANY structural badness — too short, too LONG ({@code length} past
     * {@link #MAX_DATAGRAM_BYTES}: no lawful sender emits one, {@link #encode} throws past it, so a
     * longer datagram — valid CRC or not — reaches no channel handler), bad magic, wrong version,
     * unknown channel, bad CRC — the silent-drop posture; the caller counts drops. A non-null return
     * has a verified CRC; session-id/roundId acceptance stays with the endpoint.
     *
     */
    public static Frame decode(byte[] data, int length) {
        if (data == null || length < FRAMING_BYTES || length > MAX_DATAGRAM_BYTES || length > data.length) {
            return null;
        }
        if (getU16(data, 0) != MAGIC || (data[2] & 0xFF) != VERSION) {
            return null;
        }
        WireChannel channel = WireChannel.fromWireOrNull(data[3] & 0xFF);
        if (channel == null) {
            return null;
        }
        CRC32 crc = new CRC32();
        crc.update(data, 0, length - CRC_BYTES);
        if (crc.getValue() != getU32(data, length - CRC_BYTES)) {
            return null;
        }
        int payloadLen = length - FRAMING_BYTES;
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            System.arraycopy(data, HEADER_BYTES, payload, 0, payloadLen);
        }
        return new Frame(channel, data[4] & 0xFF, getU64(data, 8), getU32(data, 16),
                getU32(data, 20), (int) getU32(data, 24), getU32(data, 28), payload);
    }

    private static void requireUnsigned32(String name, long value) {
        if (value < 0L || value > UNSIGNED_32_MAX) {
            throw new IllegalArgumentException(name + " must fit unsigned 32-bit: " + value);
        }
    }

    /** Big-endian 2-byte write — public for the handshake payload codecs (v3 WELCOME
     * {@code u16 tickRate} tail; see the modified-UTF-8 note below). */
    public static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    private static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    /** Big-endian 8-byte write — public for the handshake payload codecs (see the note below). */
    public static void putU64(byte[] b, int off, long v) {
        putU32(b, off, v >>> 32);
        putU32(b, off + 4, v & UNSIGNED_32_MAX);
    }

    private static int getU16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /** Unsigned 4-byte read, widened to long (the B1 sign-extension fold). */
    private static long getU32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long getU64(byte[] b, int off) {
        return (getU32(b, off) << 32) | getU32(b, off + 4);
    }

    // ------------------------------------------------- modified UTF-8 (handshake payloads)
    //
    // The HELLO/WELCOME payload codecs (HandshakeCodec.HelloPayload/WelcomePayload)
    // authored their strings with DataOutputStream.writeUTF / DataInputStream.readUTF until B3.
    // The in-game script classloader (com.fs.starfarer.loading.scripts.B) denies ALL of java.io
    // except a small allowlist — DataInputStream/DataOutputStream/ByteArrayOutputStream (and
    // EOFException) are unloadable from mod code — and the client-mod datagram sibling must
    // author HELLOs and decode WELCOMEs in-game. These helpers keep the wire bytes writeUTF-
    // identical (u16 byte-length prefix + modified UTF-8) using only allowlisted types;
    // DatagramFrameCodecContract axis H pins byte-equality against the Data*Stream reference.

    /** Modified-UTF-8 byte length of {@code s} ({@code writeUTF}'s encoding), excluding the u16 prefix. */
    public static int modifiedUtfLength(String s) {
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            len += (c >= 0x0001 && c <= 0x007F) ? 1 : (c <= 0x07FF ? 2 : 3);
        }
        return len;
    }

    /**
     * Write {@code s} at {@code off} exactly as {@code writeUTF} would — u16 modified-UTF-8 byte
     * length, then the bytes — returning the offset past them. Encode runs on our own state, so a
     * string past the u16 cap THROWS loud (unreachable for the length-capped handshake fields).
     */
    public static int putModifiedUtf(byte[] dst, int off, String s) {
        int byteLen = modifiedUtfLength(s);
        if (byteLen > 0xFFFF) {
            throw new IllegalArgumentException(
                    "modified-UTF-8 over the u16 length cap: " + byteLen + " B");
        }
        putU16(dst, off, byteLen);
        off += 2;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                dst[off++] = (byte) c;
            } else if (c <= 0x07FF) {
                dst[off++] = (byte) (0xC0 | (c >> 6));
                dst[off++] = (byte) (0x80 | (c & 0x3F));
            } else {
                dst[off++] = (byte) (0xE0 | (c >> 12));
                dst[off++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                dst[off++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        return off;
    }

    /**
     * Sequential reader over one payload's bytes for the handshake codecs — the sandbox-clean
     * sibling of {@code DataInputStream}'s readByte/readLong/readUTF (see the modified-UTF-8 note
     * above). Truncation or a malformed UTF group throws {@link IOException} (allowlisted), which
     * the payload codecs map to their {@code null} spoofable-input verdict.
     */
    public static final class PayloadReader {

        private final byte[] data;
        private int off;

        public PayloadReader(byte[] data) {
            this.data = data == null ? new byte[0] : data;
        }

        public int readU8() throws IOException {
            require(1);
            return data[off++] & 0xFF;
        }

        public int readU16() throws IOException {
            require(2);
            int v = getU16(data, off);
            off += 2;
            return v;
        }

        public long readU64() throws IOException {
            require(8);
            long v = getU64(data, off);
            off += 8;
            return v;
        }

        /** Read one {@code writeUTF}-encoded string, {@code readUTF}-exact for valid input. */
        public String readUtf() throws IOException {
            require(2);
            int byteLen = getU16(data, off);
            off += 2;
            require(byteLen);
            int end = off + byteLen;
            StringBuilder out = new StringBuilder(byteLen);
            while (off < end) {
                int b1 = data[off++] & 0xFF;
                if ((b1 & 0x80) == 0) {
                    out.append((char) b1);
                } else if ((b1 & 0xE0) == 0xC0) {
                    if (end - off < 1) {
                        throw new IOException("partial 2-byte UTF group at end");
                    }
                    int b2 = data[off++] & 0xFF;
                    if ((b2 & 0xC0) != 0x80) {
                        throw new IOException("malformed 2-byte UTF group");
                    }
                    out.append((char) (((b1 & 0x1F) << 6) | (b2 & 0x3F)));
                } else if ((b1 & 0xF0) == 0xE0) {
                    if (end - off < 2) {
                        throw new IOException("partial 3-byte UTF group at end");
                    }
                    int b2 = data[off++] & 0xFF;
                    int b3 = data[off++] & 0xFF;
                    if ((b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) {
                        throw new IOException("malformed 3-byte UTF group");
                    }
                    out.append((char) (((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)));
                } else {
                    throw new IOException("malformed UTF first byte 0x" + Integer.toHexString(b1));
                }
            }
            return out.toString();
        }

        /** Read {@code n} raw bytes (v12 HELLO/WELCOME cookie tails); {@code n < 0}
         * or short input = the same {@link IOException} as every other short read. */
        public byte[] readBytes(int n) throws IOException {
            if (n < 0) {
                throw new IOException("negative byte run");
            }
            require(n);
            byte[] out = new byte[n];
            System.arraycopy(data, off, out, 0, n);
            off += n;
            return out;
        }

        /** Bytes left unread — the codecs' trailing-junk check ({@code available() != 0} before B3). */
        public int remaining() {
            return data.length - off;
        }

        private void require(int n) throws IOException {
            if (data.length - off < n) {
                throw new IOException("unexpected end of payload");
            }
        }
    }
}
