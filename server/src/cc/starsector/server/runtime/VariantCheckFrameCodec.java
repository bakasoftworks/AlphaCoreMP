package cc.starsector.server.runtime;

/**
 * W15 v3 {@code VCHK} — the custom-variant PRE-ADMISSION dry-run frame.
 * The joiner
 * window asks the door "would your POLICY take this local custom?" BEFORE any claim, the twin of
 * the door PROBE (a HELLO flag cannot carry a body; a HELLO tail would bump the handshake version
 * and the whole bounce ladder — so this rides its OWN envelope channel,
 * {@link DatagramFrameCodec.WireChannel#VCHK}, dispatched beside HELLO before any session lookup).
 *
 * <p><b>Wire (UP, one custom per datagram) = {@code VCHK13}:</b>
 * magic {@link #MAGIC13}, and NO password on the wire — {@code magic13 ‖ u64 nonce ‖ u8 C ‖ cookie ‖ u8 P ‖ print ‖
 * u64 seq ‖ Ya[32] ‖ wrapM[48] ‖ wrapA[48] ‖ sealedBody} (the VSUB frame sealed under the per-fetch content key). The
 * typed password only decides WHICH door key opens a wrap ({@code Handshake13.sealWrapsOrNull} /
 * {@code DoorShares13.openWraps}); the door opens the wraps FIRST and decodes the VSUB bytes ONLY once one opened (an
 * unauthenticated body decode + policy walk would be a CPU amplifier). This class does
 * no crypto and holds no secret: {@link #encode13} lays out parts the caller already sealed, {@link #decode13OrNull}
 * is a fixed-layout parse, and the two AADs both ends feed the crypto ({@link #ada13}, {@link #bodyAad13}, and the
 * decoded record's {@code ada} / {@code bodyAad}) are cut HERE only. Both ends keep their own VSUB codec
 * ({@code VariantSubmitFrameCodec} server-side, {@code VariantSubmitWriter} in the mod jar) — one grammar, one
 * decoder per side. Bundled WHOLE into the mod jar; NO import: its sizes are compile-time constants of
 * {@link Handshake13} / {@link HandshakeCrypto} (inlined; both are folded beside this class), its byte runs are
 * copied with {@link System#arraycopy}, and every exception text is constant.
 *
 * <p><b>Reply (DOWN unicast):</b> the existing VACK frame ({@code VariantReceiptFrameCodec}),
 * carried on the SAME channel with the envelope {@code sessionId} = the echoed nonce and
 * {@code seq} = the echoed submitSeq — the join-window consumer is nonce-bound and dialed-source
 * -only (the PROBE consumer L3 law). No new reply codec, no new reject token.
 *
 * <p>Decode is fail-closed and NEVER throws ({@link #decode13OrNull}): pre-admission spoofable
 * input takes the silent-drop posture (there is no session to fail-close).
 *
 * <p><b>The version-12 frame is DELETED (THE FLIP stage Y):</b> its preamble
 * {@code u32 magic "VCHK" (0x5643484B) | u64 nonce | utf password | utf fingerprint | VSUB} carried the match
 * password in the clear, so the product — and the kit jar that bundles this class — holds neither its encoder nor its
 * decoder. A stale kit's version-12 check is a counted silent drop at the door ({@code PidsVariantCheck}: any magic
 * but {@link #MAGIC13}); the literal-12 fixture that proves it is {@code server/test} {@code V12Wire.variantCheck}.
 */
public final class VariantCheckFrameCodec {

    /** The embedded VSUB frame's own budget ({@code VariantSubmitFrameCodec.MAX_FRAME_BYTES},
     * contract-pinned equal); a VSUB near this cap cannot fit beside the preamble inside
     * {@link DatagramFrameCodec#MAX_PAYLOAD_BYTES} — the client then withholds the check
     * ("checked at submit"), never a multi-frame blob. */
    public static final int SUBMIT_FRAME_MAX_BYTES = 1164;
    /** The smallest VSUB frame that can carry a magic: below this the tail is garbage. */
    public static final int SUBMIT_FRAME_MIN_BYTES = 4;
    /** The whole VCHK payload must fit one transport datagram. */
    public static final int MAX_PAYLOAD_BYTES = DatagramFrameCodec.MAX_PAYLOAD_BYTES;

    private VariantCheckFrameCodec() {
    }

    // --- version 13: VCHK13 --------------

    /** Frame magic {@code "VC13"}, distinct from every DOWN-family and UP magic — and from the deleted
     * version-12 {@code "VCHK"} (0x5643484B), which this codec refuses like any other foreign magic. */
    public static final int MAGIC13 = 0x56433133; // "VC13"
    /**
     * ADa's purpose byte, ASCII {@code V}. The HELLO envelope's is {@code H} ({@code Handshake13}), and ADa enters
     * both ISKs, so wraps sealed for one envelope never open as the other's (row DS-V2).
     */
    public static final byte AD_PURPOSE_VCHK = 0x56;
    /** The COOKIE13 cookie rides verbatim: 1..32 B (the door mints {@code DoorCookie.BYTES} = 17). */
    public static final int COOKIE13_MAX_BYTES = Handshake13.COOKIE_MAX_BYTES;
    /** The print rides as an A-string: at most this many chars, each 0x21..0x7E. */
    public static final int PRINT13_MAX_CHARS = Handshake13.PRINT_MAX;
    /** The smallest sealed body: the smallest VSUB frame + the 16 B AEAD tag = 20 B. */
    public static final int SEALED_BODY13_MIN_BYTES = SUBMIT_FRAME_MIN_BYTES + HandshakeCrypto.TAG_BYTES;
    /** {@code magic + u64 nonce + u8 C + u8 P}: the head beside the cookie and the print (ADa = {@code 'V'} + the head). */
    private static final int HEAD13_FIXED_BYTES = 4 + 8 + 1 + 1;
    /** Offset of {@code u8 C}; the cookie follows it. */
    private static final int COOKIE_LEN13_AT = 12;
    /** {@code u64 seq + Ya + wrapM + wrapA} = 136 B: between the head and the sealed body. */
    private static final int ENVELOPE13_BYTES = 8 + HandshakeCrypto.POINT_BYTES + 2 * Handshake13.WRAP_BYTES;
    /** C = 1, P = 0 and the smallest sealed body: 171 B. */
    public static final int PAYLOAD13_MIN_BYTES =
            HEAD13_FIXED_BYTES + 1 + ENVELOPE13_BYTES + SEALED_BODY13_MIN_BYTES;

    /**
     * One decoded VCHK13: every clear field plus the two byte runs the door feeds the crypto — {@code ada} =
     * {@code 'V' ‖ payload[0 .. 14+C+P)} (magic through print; the wraps' AAD) and {@code bodyAad} =
     * {@code payload[0 .. 150+C+P)} (every byte before {@code sealedBody}; the body's AAD). The accessors return the
     * arrays THEMSELVES (no copy). VALUE-DOMAIN: {@code nonce} any signed long; {@code seq} 0..{@link Long#MAX_VALUE}.
     * {@code equals} / {@code hashCode} compare content and {@code toString} is constant text (classloader B: no
     * record bootstrap rides the kit; no cookie, print or envelope byte in any text).
     *
     */
    public record VariantCheck13(long nonce, byte[] cookie, String print, long seq, byte[] ya, byte[] wrapM,
            byte[] wrapA, byte[] sealedBody, byte[] ada, byte[] bodyAad) {
        @Override
        public boolean equals(Object o) {
            return o instanceof VariantCheck13 other && nonce == other.nonce && seq == other.seq
                    && sameBytes(cookie, other.cookie)
                    && (print == null ? other.print == null : print.equals(other.print))
                    && sameBytes(ya, other.ya) && sameBytes(wrapM, other.wrapM) && sameBytes(wrapA, other.wrapA)
                    && sameBytes(sealedBody, other.sealedBody) && sameBytes(ada, other.ada)
                    && sameBytes(bodyAad, other.bodyAad);
        }

        @Override
        public int hashCode() {
            int h = 31 * Long.hashCode(nonce) + Long.hashCode(seq);
            h = 31 * h + bytesHash(cookie);
            h = 31 * h + (print == null ? 0 : print.hashCode());
            h = 31 * h + bytesHash(ya);
            h = 31 * h + bytesHash(wrapM);
            h = 31 * h + bytesHash(wrapA);
            h = 31 * h + bytesHash(sealedBody);
            h = 31 * h + bytesHash(ada);
            return 31 * h + bytesHash(bodyAad);
        }

        /** Constant text: never the cookie, the print or a byte of the envelope. */
        @Override
        public String toString() {
            return "VariantCheckFrameCodec.VariantCheck13";
        }
    }

    /**
     * The wraps' AAD {@code ADa = 'V' ‖ magic13 ‖ u64 nonce ‖ u8 C ‖ cookie ‖ u8 P ‖ print}: the kit seals both wraps
     * under it ({@code Handshake13.sealWrapsOrNull}); {@link #decode13OrNull} cuts the SAME bytes off the wire
     * ({@link VariantCheck13#ada()}). No seq: it is constant per fetch, so ONE wrap seal serves every custom of a
     * fetch. {@code IllegalArgumentException} naming the field: a cookie outside 1..32 B, a print that is not an
     * A-string(128).
     *
     */
    public static byte[] ada13(long nonce, byte[] cookie, String print) {
        requireCookie13(cookie);
        requirePrint13(print);
        byte[] head = new byte[HEAD13_FIXED_BYTES + cookie.length + print.length()];
        int headEnd = putHead13(head, nonce, cookie, print);
        return adaOf(head, headEnd);
    }

    /**
     * The body's AAD = {@code payload[0 .. 150+C+P)}, every byte {@link #encode13} writes before the sealed body —
     * the kit passes it to {@code Handshake13.sealBody(ck, seq, aad, vsub)}; the door reads the same bytes as
     * {@link VariantCheck13#bodyAad()}. {@code IllegalArgumentException} naming the field: as {@link #ada13}, plus a
     * negative {@code seq}, a {@code ya} not 32 B, a wrap not 48 B.
     *
     */
    public static byte[] bodyAad13(long nonce, byte[] cookie, String print, long seq, byte[] ya, byte[] wrapM,
            byte[] wrapA) {
        requireCookie13(cookie);
        requirePrint13(print);
        requireEnvelope13(seq, ya, wrapM, wrapA);
        byte[] out = new byte[HEAD13_FIXED_BYTES + cookie.length + print.length() + ENVELOPE13_BYTES];
        putEnvelope13(out, putHead13(out, nonce, cookie, print), seq, ya, wrapM, wrapA);
        return out;
    }

    /**
     * Encode one VCHK13 payload from parts the caller already sealed: {@link #bodyAad13}'s bytes, then
     * {@code sealedBody}. The caller frames it on {@code WireChannel.VCHK} as version 12 does.
     * {@code IllegalArgumentException} (constant text naming the field) on a value {@link #bodyAad13} refuses, a
     * sealed body under {@value #SEALED_BODY13_MIN_BYTES} B, or a payload over {@link #MAX_PAYLOAD_BYTES}.
     *
     * <p>CALLER LAW — size BEFORE seal: check the VSUB frame against {@link #maxSubmitFrame13} BEFORE
     * {@code Handshake13.sealBody}, and withhold a custom that does not fit (it reads "checked at launch"). An oversize
     * refused HERE has already burned a seal at {@code (ck, seq)}; {@code sealBody}'s own law is one {@code (ck, seq)}
     * seals ONE body, ever, so that seq must never carry another body.
     *
     */
    public static byte[] encode13(long nonce, byte[] cookie, String print, long seq, byte[] ya, byte[] wrapM,
            byte[] wrapA, byte[] sealedBody) {
        requireCookie13(cookie);
        requirePrint13(print);
        requireEnvelope13(seq, ya, wrapM, wrapA);
        if (sealedBody == null || sealedBody.length < SEALED_BODY13_MIN_BYTES) {
            throw new IllegalArgumentException("sealedBody");
        }
        int sealedAt = HEAD13_FIXED_BYTES + cookie.length + print.length() + ENVELOPE13_BYTES;
        if (sealedBody.length > MAX_PAYLOAD_BYTES - sealedAt) {
            throw new IllegalArgumentException("variant-check-13 payload over the datagram budget");
        }
        byte[] out = new byte[sealedAt + sealedBody.length];
        putEnvelope13(out, putHead13(out, nonce, cookie, print), seq, ya, wrapM, wrapA);
        System.arraycopy(sealedBody, 0, out, sealedAt, sealedBody.length);
        return out;
    }

    /**
     * Fixed-layout parse of one VCHK13 payload, NO crypto; {@code null} for anything else, NEVER a throw. Refuses, in
     * order: a null payload, a length outside {@value #PAYLOAD13_MIN_BYTES}..{@link #MAX_PAYLOAD_BYTES}, a magic other
     * than {@link #MAGIC13} (the version-12 magic included), C outside 1..32, P over 128, a sealed body under
     * {@value #SEALED_BODY13_MIN_BYTES} B (which also bounds every read before it), a print byte outside 0x21..0x7E, a
     * negative seq. The sealed body's own laws (tag, VSUB magic, schema, CRC) are the door's, after the open.
     * Allocates only copies of the payload's own bytes.
     *
     */
    public static VariantCheck13 decode13OrNull(byte[] payload) {
        if (payload == null || payload.length < PAYLOAD13_MIN_BYTES || payload.length > MAX_PAYLOAD_BYTES
                || u32(payload, 0) != MAGIC13) {
            return null;
        }
        int cookieLen = payload[COOKIE_LEN13_AT] & 0xFF;
        if (cookieLen < 1 || cookieLen > COOKIE13_MAX_BYTES) {
            return null;
        }
        int printLenAt = COOKIE_LEN13_AT + 1 + cookieLen;
        int printLen = payload[printLenAt] & 0xFF;
        if (printLen > PRINT13_MAX_CHARS) {
            return null;
        }
        int headEnd = HEAD13_FIXED_BYTES + cookieLen + printLen;
        int sealedAt = headEnd + ENVELOPE13_BYTES;
        if (payload.length - sealedAt < SEALED_BODY13_MIN_BYTES) {
            return null;
        }
        char[] chars = new char[printLen];
        for (int i = 0; i < printLen; i++) {
            int v = payload[printLenAt + 1 + i] & 0xFF;
            if (v < 0x21 || v > 0x7E) {
                return null;
            }
            chars[i] = (char) v;
        }
        long seq = u64(payload, headEnd);
        if (seq < 0L) {
            return null;
        }
        int yaAt = headEnd + 8;
        int wrapMAt = yaAt + HandshakeCrypto.POINT_BYTES;
        int wrapAAt = wrapMAt + Handshake13.WRAP_BYTES;
        return new VariantCheck13(u64(payload, 4), slice(payload, COOKIE_LEN13_AT + 1, cookieLen),
                new String(chars), seq, slice(payload, yaAt, HandshakeCrypto.POINT_BYTES),
                slice(payload, wrapMAt, Handshake13.WRAP_BYTES), slice(payload, wrapAAt, Handshake13.WRAP_BYTES),
                slice(payload, sealedAt, payload.length - sealedAt), adaOf(payload, headEnd),
                slice(payload, 0, sealedAt));
    }

    /**
     * VCHK13 bytes beyond the VSUB frame at this cookie + print length: {@code 166 + C + P} (head 14, seq + Ya + both
     * wraps 136, the body tag 16) — {@code 183 + P} at the door's 17 B cookie (row C4).
     * {@code IllegalArgumentException}: {@code cookieLen} outside 1..32, {@code printLen} outside 0..128.
     *
     */
    public static int overhead13(int cookieLen, int printLen) {
        if (cookieLen < 1 || cookieLen > COOKIE13_MAX_BYTES) {
            throw new IllegalArgumentException("cookieLen");
        }
        if (printLen < 0 || printLen > PRINT13_MAX_CHARS) {
            throw new IllegalArgumentException("printLen");
        }
        return HEAD13_FIXED_BYTES + cookieLen + printLen + ENVELOPE13_BYTES + HandshakeCrypto.TAG_BYTES;
    }

    /**
     * The largest VSUB frame one VCHK13 carries at this cookie + print length: {@link #MAX_PAYLOAD_BYTES} minus
     * {@link #overhead13} = {@code 981 - P} at the 17 B cookie, never above {@link #SUBMIT_FRAME_MAX_BYTES}. The
     * kit checks it BEFORE any seal ({@link #encode13}'s caller law). Throws as {@link #overhead13}.
     *
     */
    public static int maxSubmitFrame13(int cookieLen, int printLen) {
        return Math.min(SUBMIT_FRAME_MAX_BYTES, MAX_PAYLOAD_BYTES - overhead13(cookieLen, printLen));
    }

    // --- version-13 internals: ------------------------------

    /** Writes {@code magic13 ‖ nonce ‖ u8 C ‖ cookie ‖ u8 P ‖ print} at 0 (both already checked); returns {@code 14 + C + P}. */
    private static int putHead13(byte[] out, long nonce, byte[] cookie, String print) {
        out[0] = (byte) (MAGIC13 >>> 24);
        out[1] = (byte) (MAGIC13 >>> 16);
        out[2] = (byte) (MAGIC13 >>> 8);
        out[3] = (byte) MAGIC13;
        DatagramFrameCodec.putU64(out, 4, nonce);
        out[COOKIE_LEN13_AT] = (byte) cookie.length;
        System.arraycopy(cookie, 0, out, COOKIE_LEN13_AT + 1, cookie.length);
        int at = COOKIE_LEN13_AT + 1 + cookie.length;
        out[at++] = (byte) print.length();
        for (int i = 0; i < print.length(); i++) {
            out[at++] = (byte) print.charAt(i);
        }
        return at;
    }

    /** Writes {@code u64 seq ‖ Ya ‖ wrapM ‖ wrapA} at {@code at} (already checked). */
    private static void putEnvelope13(byte[] out, int at, long seq, byte[] ya, byte[] wrapM, byte[] wrapA) {
        DatagramFrameCodec.putU64(out, at, seq);
        System.arraycopy(ya, 0, out, at + 8, ya.length);
        System.arraycopy(wrapM, 0, out, at + 8 + ya.length, wrapM.length);
        System.arraycopy(wrapA, 0, out, at + 8 + ya.length + wrapM.length, wrapA.length);
    }

    /** {@code 'V' ‖ bytes[0 .. headEnd)}: the ONE cut of ADa, for the kit's {@link #ada13} and the door's decode. */
    private static byte[] adaOf(byte[] bytes, int headEnd) {
        byte[] ada = new byte[1 + headEnd];
        ada[0] = AD_PURPOSE_VCHK;
        System.arraycopy(bytes, 0, ada, 1, headEnd);
        return ada;
    }

    private static void requireCookie13(byte[] cookie) {
        if (cookie == null || cookie.length < 1 || cookie.length > COOKIE13_MAX_BYTES) {
            throw new IllegalArgumentException("cookie");
        }
    }

    /** A-string(128): non-null, at most 128 chars, every char 0x21..0x7E (one byte each on the wire). */
    private static void requirePrint13(String print) {
        if (print == null || print.length() > PRINT13_MAX_CHARS) {
            throw new IllegalArgumentException("print");
        }
        for (int i = 0; i < print.length(); i++) {
            char c = print.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new IllegalArgumentException("print");
            }
        }
    }

    private static void requireEnvelope13(long seq, byte[] ya, byte[] wrapM, byte[] wrapA) {
        if (seq < 0L) {
            throw new IllegalArgumentException("seq");
        }
        if (ya == null || ya.length != HandshakeCrypto.POINT_BYTES) {
            throw new IllegalArgumentException("ya");
        }
        if (wrapM == null || wrapM.length != Handshake13.WRAP_BYTES) {
            throw new IllegalArgumentException("wrapM");
        }
        if (wrapA == null || wrapA.length != Handshake13.WRAP_BYTES) {
            throw new IllegalArgumentException("wrapA");
        }
    }

    private static int u32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static long u64(byte[] b, int off) {
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    /** Content equality without {@code java.util.Arrays} (the file's import set stays EMPTY). */
    private static boolean sameBytes(byte[] a, byte[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static int bytesHash(byte[] a) {
        if (a == null) {
            return 0;
        }
        int h = 1;
        for (byte x : a) {
            h = 31 * h + x;
        }
        return h;
    }
}
