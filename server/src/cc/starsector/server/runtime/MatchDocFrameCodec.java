package cc.starsector.server.runtime;

import cc.starsector.server.match.MatchDocumentLaws;

import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The match-document reply's frame codec: the server's current redacted match document, raw-deflated,
 * cut into at most {@link #CHUNKS_MAX} chunks, each sealed ALONE and carried in its own {@code DESC}
 * frame at schema {@link #SCHEMA} - the reply a password-proven join probe receives, so a player's join
 * window fetches the match itself and nobody hands the document around as a file.
 *
 * <p><b>Frame</b> (big-endian; byte-for-byte the reserved-control envelope {@link ReservedControlFrameCodec}
 * writes): {@code magic u32 = "DESC" | schema u16 = 2 | headerBytes u16 = 16 | wireTypeCode u32 |
 * bodyBytes u32 | body | CRC32(body) u32}. Body = the 24 B header {@code v u8 = 1 | index u8 | count u8 |
 * flags u8 = 0x01 (raw deflate) | deflatedTotal u16 | textTotal u16 | docSalt 16 B}, then the sealed part
 * {@code ciphertext (<= CHUNK_MAX) || tag 16 B}. One frame is at most {@link #FRAME_MAX} = one datagram
 * payload, so a chunk never fragments.
 *
 * <p><b>Sealing</b> (ChaCha20-Poly1305 via {@link HandshakeCrypto#seal}): {@code key = HKDF-SHA256(root,
 * docSalt, "cc-hs14 match doc", 32)} once per reply (and once per decode), {@code nonce = eleven zero bytes
 * || index}, AAD = the 24 B header, plain = that chunk's slice of the deflated document. WHY per-chunk AEAD
 * under a per-reply salt: every frame verifies ALONE (a lost datagram costs one chunk, never the reply, and
 * a frame needs no neighbour to be judged), and the sender draws a fresh salt per reply, so the key is fresh
 * too - a chunk from another reply, a forged frame or a replayed one fails its tag and the caller DROPS it
 * (counted, never a refusal of the join). The index in the nonce keeps the up to eight seals under one key
 * distinct; the header in the AAD binds each chunk to its place, its totals and its salt. The salt's
 * freshness is the SENDER's law: two seals under one root + salt over different bytes would reuse a nonce.
 *
 * <p>Shared: bundled into the client mod jar and run under the game's script classloader, so this class is
 * byte[] in / byte[] out - {@code java.util.zip}, {@link WireByteOps}, {@link HandshakeCrypto} and the
 * document caps of {@link MatchDocumentLaws} only; no stream IO, no regex, no reflection, and no reference
 * to the server-only message registry (the wire type code is computed here from the twin message id, and a
 * contract row pins both equal). It counts nothing: every non-OK reason and every null is the caller's to
 * count.
 *
 *
 */
public final class MatchDocFrameCodec {

    /** Frame magic {@code "DESC"}: the reserved descriptor magic's first live consumer (equal to
     *  {@code ReservedControlFrameCodec.MAGIC_DESCRIPTOR}; re-declared so this class never loads that server-only codec). */
    public static final int MAGIC = 0x44455343; // "DESC"
    /** This frame's schema; schema 1 stays the dormant in-session reservation. */
    public static final int SCHEMA = 2;
    /** The frozen 16 B envelope header (magic, schema, headerBytes, wireTypeCode, bodyBytes). */
    public static final int ENVELOPE_HEADER_BYTES = 16;
    /** The envelope header plus the u32 CRC32 trailer. */
    public static final int ENVELOPE_BYTES = ENVELOPE_HEADER_BYTES + 4;
    /** Twin of {@code MatchDocumentMessage.MESSAGE_ID} (the server-only registry entry); a contract row pins equality. */
    public static final String MESSAGE_ID = "server.match_document";
    /** CRC32 of {@link #MESSAGE_ID}'s UTF-8 bytes - the registry's wire-type law, computed locally. */
    public static final int WIRE_TYPE_CODE;
    /** The body header: v, index, count, flags, deflatedTotal, textTotal, docSalt. */
    public static final int BODY_HEADER_BYTES = 24;
    /** The body layout version (body byte 0). */
    public static final int BODY_VERSION = 1;
    /** The one flag value this version accepts: the chunks carry a raw (headerless) deflate stream. */
    public static final int FLAG_RAW_DEFLATE = 0x01;
    /** The per-reply salt carried in every chunk's header. */
    public static final int SALT_BYTES = 16;
    /** The Poly1305 tag closing every sealed chunk. */
    public static final int TAG_BYTES = 16;
    /**
     * The exchange root's floor: the handshake's root is {@code ISK (64 B) || mix} ({@code Handshake13.requireRoot},
     * "at least the 64 B ISK"), never a fixed 32 B - the lane's 32 B law would have thrown on EVERY live
     * encode / decode. Shorter = a caller bug; longer is the law.
     */
    public static final int ROOT_MIN_BYTES = HandshakeCrypto.ISK_BYTES;
    /** One chunk's plain bytes at most. */
    public static final int CHUNK_MAX = MatchDocumentLaws.CHUNK_MAX;
    /** Chunks per reply at most. */
    public static final int CHUNKS_MAX = MatchDocumentLaws.CHUNKS_MAX;
    /** The deflated document's size at most ({@code CHUNKS_MAX * CHUNK_MAX}). */
    public static final int DEFLATED_MAX = MatchDocumentLaws.DEFLATED_MAX;
    /** The document text's UTF-8 size at most - the one inflate buffer's ceiling. */
    public static final int TEXT_MAX = MatchDocumentLaws.TEXT_MAX;
    /** One frame at most: one datagram payload ({@code 16 + 24 + CHUNK_MAX + 16 + 4}). */
    public static final int FRAME_MAX = DatagramFrameCodec.MAX_PAYLOAD_BYTES;
    /** Distinct reply salts one {@link Reassembler} holds at once (one probe flight's tries, with slack). */
    public static final int SALTS_MAX = 4;

    /** The frame decoded and its tag verified. */
    public static final int OK = 0;
    /** The envelope is wrong: length, magic, schema, header size, wire type, body size or CRC. */
    public static final int BAD_ENVELOPE = 1;
    /** The body header names a version or flags this codec does not speak. */
    public static final int BAD_HEADER = 2;
    /** A header count / index / total is outside its law, or the sealed part's length disagrees with it. */
    public static final int BAD_BOUNDS = 3;
    /** The seal did not open under the given root: another reply's, a forged or a tampered frame. */
    public static final int BAD_TAG = 4;

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int MIN_FRAME_BYTES = ENVELOPE_BYTES + BODY_HEADER_BYTES + TAG_BYTES;
    private static final int OFF_BODY = ENVELOPE_HEADER_BYTES;
    private static final int OFF_SALT = OFF_BODY + 8;
    private static final int OFF_SEALED = OFF_BODY + BODY_HEADER_BYTES;
    /** Both literals are printable ASCII, so these bytes ARE their UTF-8 bytes (no charset class needed). */
    private static final byte[] LABEL = ascii("cc-hs14 match doc");

    private static final Decoded DECODED_BAD_ENVELOPE = new Decoded(BAD_ENVELOPE, null);
    private static final Decoded DECODED_BAD_HEADER = new Decoded(BAD_HEADER, null);
    private static final Decoded DECODED_BAD_BOUNDS = new Decoded(BAD_BOUNDS, null);
    private static final Decoded DECODED_BAD_TAG = new Decoded(BAD_TAG, null);

    static {
        byte[] id = ascii(MESSAGE_ID);
        CRC32 crc = new CRC32();
        crc.update(id, 0, id.length);
        WIRE_TYPE_CODE = (int) crc.getValue();
    }

    private MatchDocFrameCodec() {
    }

    /** The HKDF info label ({@code "cc-hs14 match doc"}), as a copy. */
    public static byte[] label() {
        return LABEL.clone();
    }

    /** A bounded token for meters and logs; never null ({@code "unknown"} outside the five codes). */
    public static String reasonName(int reason) {
        return switch (reason) {
            case OK -> "ok";
            case BAD_ENVELOPE -> "bad_envelope";
            case BAD_HEADER -> "bad_header";
            case BAD_BOUNDS -> "bad_bounds";
            case BAD_TAG -> "bad_tag";
            default -> "unknown";
        };
    }

    /**
     * One verified chunk: its place ({@code index} of {@code count}), the reply's totals, the reply's salt and
     * the opened plain bytes. The constructor enforces every law {@link #decode} checks (so a held chunk is
     * always well-formed and a complete set concatenates to exactly {@code deflatedTotal} bytes) and copies
     * both arrays; the accessors hand out copies, so no holder can change a chunk after the fact.
     */
    public record Chunk(int index, int count, int deflatedTotal, int textTotal, byte[] docSalt, byte[] plain) {
        public Chunk {
            if (count < 1 || count > CHUNKS_MAX || index < 0 || index >= count
                    || deflatedTotal < 1 || deflatedTotal > DEFLATED_MAX || count != chunkCount(deflatedTotal)
                    || textTotal < 1 || textTotal > TEXT_MAX) {
                throw new IllegalArgumentException("chunk header");
            }
            if (docSalt == null || docSalt.length != SALT_BYTES) {
                throw new IllegalArgumentException("docSalt");
            }
            if (plain == null || plain.length != plainLength(index, count, deflatedTotal)) {
                throw new IllegalArgumentException("plain");
            }
            docSalt = docSalt.clone();
            plain = plain.clone();
        }

        @Override
        public byte[] docSalt() {
            return docSalt.clone();
        }

        @Override
        public byte[] plain() {
            return plain.clone();
        }
    }

    /** A decode's outcome: {@code chunk} is non-null exactly when {@code reason == OK}. */
    public record Decoded(int reason, Chunk chunk) {
        public Decoded {
            if (reason < OK || reason > BAD_TAG || (reason == OK) != (chunk != null)) {
                throw new IllegalArgumentException("decoded");
            }
        }
    }

    /**
     * Encode one reply: {@code deflated} cut into {@code ceil(length / CHUNK_MAX)} chunks (1..{@link #CHUNKS_MAX}),
     * chunk {@code i} = bytes {@code [i * CHUNK_MAX, min((i + 1) * CHUNK_MAX, length))}, each sealed under the
     * reply key and framed alone. Every argument is the CALLER's (never network bytes): a null, a root shorter
     * than {@link #ROOT_MIN_BYTES}, a salt that is not {@link #SALT_BYTES}, a deflated length outside
     * 1..{@link #DEFLATED_MAX} or a text total outside 1..{@link #TEXT_MAX} = {@code IllegalArgumentException}.
     * {@code docSalt} must be fresh per reply (SecureRandom) - see the class javadoc. Returns the complete
     * frames in index order, each at most {@link #FRAME_MAX} bytes.
     */
    public static byte[][] encodeReply(byte[] root, byte[] docSalt, byte[] deflated, int textTotal) {
        requireRoot(root);
        if (docSalt == null || docSalt.length != SALT_BYTES) {
            throw new IllegalArgumentException("docSalt");
        }
        if (deflated == null || deflated.length < 1 || deflated.length > DEFLATED_MAX) {
            throw new IllegalArgumentException("deflated");
        }
        if (textTotal < 1 || textTotal > TEXT_MAX) {
            throw new IllegalArgumentException("textTotal");
        }
        int count = chunkCount(deflated.length);
        byte[] key = HandshakeCrypto.hkdf(root, docSalt, LABEL, KEY_BYTES);
        byte[][] frames = new byte[count][];
        for (int index = 0; index < count; index++) {
            int from = index * CHUNK_MAX;
            int to = Math.min(from + CHUNK_MAX, deflated.length);
            byte[] header = new byte[BODY_HEADER_BYTES];
            int at = WireByteOps.putByte(header, 0, BODY_VERSION);
            at = WireByteOps.putByte(header, at, index);
            at = WireByteOps.putByte(header, at, count);
            at = WireByteOps.putByte(header, at, FLAG_RAW_DEFLATE);
            at = WireByteOps.putShort(header, at, deflated.length);
            at = WireByteOps.putShort(header, at, textTotal);
            System.arraycopy(docSalt, 0, header, at, SALT_BYTES);
            byte[] sealed = HandshakeCrypto.seal(key, nonce(index), header, Arrays.copyOfRange(deflated, from, to));
            frames[index] = envelope(header, sealed);
        }
        return frames;
    }

    /**
     * Decode and open ONE frame under {@code root}. {@code payload} is network bytes, so this never throws on
     * it: a null, short or long payload and every broken law return a reason, and the reason names the FIRST
     * law broken, in this order - {@link #BAD_ENVELOPE} (length outside {@code 60..FRAME_MAX}, magic, schema,
     * header size, wire type, body size, CRC), {@link #BAD_HEADER} (version, flags), {@link #BAD_BOUNDS}
     * (count, index, totals, the count / deflated-total agreement, the sealed length this index must carry),
     * {@link #BAD_TAG} (the seal does not open). Nothing is sized from a field before that field is checked
     * against the constants. {@code root} is the caller's: null or shorter than {@link #ROOT_MIN_BYTES} =
     * {@code IllegalArgumentException}. A caller holding two roots (a player and an admin password) decodes
     * under each and keeps the OK one.
     */
    public static Decoded decode(byte[] root, byte[] payload) {
        requireRoot(root);
        if (payload == null || payload.length < MIN_FRAME_BYTES || payload.length > FRAME_MAX) {
            return DECODED_BAD_ENVELOPE;
        }
        int bodyBytes = WireByteOps.getInt(payload, 12);
        if (WireByteOps.getInt(payload, 0) != MAGIC
                || WireByteOps.getUnsignedShort(payload, 4) != SCHEMA
                || WireByteOps.getUnsignedShort(payload, 6) != ENVELOPE_HEADER_BYTES
                || WireByteOps.getInt(payload, 8) != WIRE_TYPE_CODE
                || bodyBytes != payload.length - ENVELOPE_BYTES
                || crc32(payload, OFF_BODY, bodyBytes) != WireByteOps.getInt(payload, payload.length - 4)) {
            return DECODED_BAD_ENVELOPE;
        }
        int version = payload[OFF_BODY] & 0xFF;
        int index = payload[OFF_BODY + 1] & 0xFF;
        int count = payload[OFF_BODY + 2] & 0xFF;
        int flags = payload[OFF_BODY + 3] & 0xFF;
        int deflatedTotal = WireByteOps.getUnsignedShort(payload, OFF_BODY + 4);
        int textTotal = WireByteOps.getUnsignedShort(payload, OFF_BODY + 6);
        if (version != BODY_VERSION || flags != FLAG_RAW_DEFLATE) {
            return DECODED_BAD_HEADER;
        }
        if (count < 1 || count > CHUNKS_MAX || index >= count
                || deflatedTotal < 1 || deflatedTotal > DEFLATED_MAX
                || textTotal < 1 || textTotal > TEXT_MAX
                || count != chunkCount(deflatedTotal)
                || bodyBytes - BODY_HEADER_BYTES != plainLength(index, count, deflatedTotal) + TAG_BYTES) {
            return DECODED_BAD_BOUNDS;
        }
        byte[] header = Arrays.copyOfRange(payload, OFF_BODY, OFF_SEALED);
        byte[] salt = Arrays.copyOfRange(payload, OFF_SALT, OFF_SEALED);
        byte[] sealed = Arrays.copyOfRange(payload, OFF_SEALED, OFF_BODY + bodyBytes);
        byte[] key = HandshakeCrypto.hkdf(root, salt, LABEL, KEY_BYTES);
        byte[] plain = HandshakeCrypto.open(key, nonce(index), header, sealed);
        if (plain == null) {
            return DECODED_BAD_TAG;
        }
        return new Decoded(OK, new Chunk(index, count, deflatedTotal, textTotal, salt, plain));
    }

    /**
     * Collects one probe flight's verified chunks and yields the FIRST complete reply. Chunks are held PER
     * SALT (one salt = one reply; replies carry no order between them), at most {@link #SALTS_MAX} salts, so
     * the memory one flight can pin is bounded by the caps alone. A salt's FIRST chunk pins its count and
     * totals; a later chunk of that salt that disagrees is dropped. A repeat of a held index with the same
     * bytes is idempotent; different bytes at a held index are dropped (first wins). Once a set is complete
     * the result never changes and every chunk of another salt is dropped. Not thread-safe: one per probe
     * flight, owned by the one collector that feeds it.
     */
    public static final class Reassembler {

        private final byte[][] salts = new byte[SALTS_MAX][];
        private final int[] counts = new int[SALTS_MAX];
        private final int[] deflatedTotals = new int[SALTS_MAX];
        private final int[] textTotals = new int[SALTS_MAX];
        private final int[] heldChunks = new int[SALTS_MAX];
        private final byte[][][] plains = new byte[SALTS_MAX][][];
        private int saltsHeld;
        private int complete = -1;
        private byte[] deflated;

        /**
         * Offer one verified chunk. Returns {@code true} when it is held (a new index of a known or new salt
         * whose count and totals equal that salt's first chunk, or an identical repeat), {@code false} when it
         * is dropped (its count / totals differ from its salt's first chunk, different bytes at a held index,
         * a new salt while {@link #SALTS_MAX} are held, or any other salt once a set is complete).
         * {@code null} is a caller bug = {@code IllegalArgumentException}.
         */
        public boolean offer(Chunk c) {
            if (c == null) {
                throw new IllegalArgumentException("chunk");
            }
            int s = saltSlot(c.docSalt);
            if (complete >= 0 && s != complete) {
                return false;
            }
            if (s < 0) {
                if (saltsHeld == SALTS_MAX) {
                    return false;
                }
                s = saltsHeld++;
                salts[s] = c.docSalt.clone();
                counts[s] = c.count;
                deflatedTotals[s] = c.deflatedTotal;
                textTotals[s] = c.textTotal;
                plains[s] = new byte[c.count][];
            } else if (c.count != counts[s] || c.deflatedTotal != deflatedTotals[s] || c.textTotal != textTotals[s]) {
                return false;
            }
            byte[][] set = plains[s];
            if (set[c.index] != null) {
                return Arrays.equals(set[c.index], c.plain);
            }
            set[c.index] = c.plain.clone();
            if (++heldChunks[s] == counts[s]) {
                // Every chunk's length is its index's law (the Chunk constructor), so the parts sum to deflatedTotal.
                byte[] all = new byte[deflatedTotals[s]];
                int at = 0;
                for (byte[] part : set) {
                    System.arraycopy(part, 0, all, at, part.length);
                    at += part.length;
                }
                deflated = all;
                complete = s;
            }
            return true;
        }

        /** The complete reply's deflated bytes in index order (a copy), or {@code null} before any set completes. */
        public byte[] deflatedOrNull() {
            return deflated == null ? null : deflated.clone();
        }

        /** The complete reply's text total, or {@code -1} before any set completes. */
        public int textTotal() {
            return complete < 0 ? -1 : textTotals[complete];
        }

        /** The complete reply's salt (a copy), or {@code null} before any set completes. */
        public byte[] docSalt() {
            return complete < 0 ? null : salts[complete].clone();
        }

        /** Distinct salts held so far (0..{@link #SALTS_MAX}). */
        public int saltsHeld() {
            return saltsHeld;
        }

        private int saltSlot(byte[] salt) {
            for (int i = 0; i < saltsHeld; i++) {
                if (Arrays.equals(salts[i], salt)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Inflate a complete reply into the document text: ONE output buffer of exactly {@code textTotal} bytes,
     * a raw inflater, and OK only when the stream FINISHED, produced exactly {@code textTotal} bytes and left
     * no input behind. Never throws on the input: {@code textTotal} outside 1..{@link #TEXT_MAX} (checked
     * before any allocation), a null, a corrupt, short, long or trailing-garbage stream = {@code null}, which
     * the caller counts.
     */
    public static byte[] inflateExact(byte[] deflated, int textTotal) {
        if (textTotal < 1 || textTotal > TEXT_MAX || deflated == null) {
            return null;
        }
        byte[] text = new byte[textTotal];
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(deflated);
            int produced = 0;
            while (produced < textTotal) {
                int n = inflater.inflate(text, produced, textTotal - produced);
                if (n == 0) {
                    break; // finished, or needs input it will never get: judged below
                }
                produced += n;
            }
            if (produced != textTotal) {
                return null;
            }
            // The end-of-stream marker may trail the last output byte: one probe byte that inflates ANYTHING
            // means the stream is longer than its declared total.
            if (!inflater.finished() && inflater.inflate(new byte[1], 0, 1) != 0) {
                return null;
            }
            if (!inflater.finished() || inflater.getRemaining() != 0) {
                return null;
            }
            return text;
        } catch (DataFormatException corrupt) {
            return null; // a corrupt stream is a null like every other failure; the caller counts it
        } finally {
            inflater.end();
        }
    }

    /**
     * Raw-deflate (level 9, no zlib wrapper) a document text the caller owns: null or longer than
     * {@link #TEXT_MAX} = {@code IllegalArgumentException}. The output starts at {@code length + 64} bytes (a
     * stored block adds 5 B per 65,535 B, so the cap never outgrows it) and grows if the stream ever needs it
     * - the returned stream is always complete, never truncated.
     */
    public static byte[] deflate(byte[] text) {
        if (text == null || text.length > TEXT_MAX) {
            throw new IllegalArgumentException("text");
        }
        Deflater deflater = new Deflater(9, true);
        try {
            deflater.setInput(text);
            deflater.finish();
            byte[] out = new byte[text.length + 64];
            int written = 0;
            while (!deflater.finished()) {
                if (written == out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                written += deflater.deflate(out, written, out.length - written);
            }
            return Arrays.copyOf(out, written);
        } finally {
            deflater.end();
        }
    }

    /** The handshake's own root law ({@code root_x = ISK_x || mix}): at least the 64 B ISK, any longer is fine. */
    private static void requireRoot(byte[] root) {
        if (root == null || root.length < ROOT_MIN_BYTES) {
            throw new IllegalArgumentException("root");
        }
    }

    private static int chunkCount(int deflatedLength) {
        return (deflatedLength + CHUNK_MAX - 1) / CHUNK_MAX;
    }

    /** The plain length chunk {@code index} of {@code count} must carry: full chunks, then the remainder. */
    private static int plainLength(int index, int count, int deflatedTotal) {
        return index < count - 1 ? CHUNK_MAX : deflatedTotal - (count - 1) * CHUNK_MAX;
    }

    private static byte[] nonce(int index) {
        byte[] nonce = new byte[NONCE_BYTES];
        nonce[NONCE_BYTES - 1] = (byte) index;
        return nonce;
    }

    private static byte[] envelope(byte[] header, byte[] sealed) {
        int bodyBytes = header.length + sealed.length;
        byte[] frame = new byte[ENVELOPE_BYTES + bodyBytes];
        int at = WireByteOps.putInt(frame, 0, MAGIC);
        at = WireByteOps.putShort(frame, at, SCHEMA);
        at = WireByteOps.putShort(frame, at, ENVELOPE_HEADER_BYTES);
        at = WireByteOps.putInt(frame, at, WIRE_TYPE_CODE);
        at = WireByteOps.putInt(frame, at, bodyBytes);
        System.arraycopy(header, 0, frame, at, header.length);
        System.arraycopy(sealed, 0, frame, at + header.length, sealed.length);
        WireByteOps.putInt(frame, at + bodyBytes, crc32(frame, OFF_BODY, bodyBytes));
        return frame;
    }

    private static int crc32(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) s.charAt(i);
        }
        return out;
    }
}
