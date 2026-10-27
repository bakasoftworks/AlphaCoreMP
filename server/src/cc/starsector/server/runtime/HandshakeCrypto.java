package cc.starsector.server.runtime;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The hs-13 join handshake's crypto core. WHY: on hs 12 the
 * match password / admin secret crosses the network readable inside HELLO; hs 13 replaces that credential with
 * a CPace-derived password exchange (draft-irtf-cfrg-cpace-21, group G_X25519 + hash SHA-512, initiator-responder
 * transcript) whose derived keys seal the HELLO body and the door's reply. This class is the PURE half every
 * later lane (codec, door, kit) calls: no wire framing, no state, no logging, no I/O.
 *
 * <p>Everything except the Elligator 2 map is the JDK's own — XDH (X25519), SHA-512, HmacSHA256,
 * ChaCha20-Poly1305 — with a FRESH engine per call (the JDK refuses a repeated ChaCha20-Poly1305 key + nonce on
 * one encrypting instance; the cost in this shape is measured).
 *
 * <p>Classloader B: this class is folded ALONE into the client jar, so it references only
 * {@code java.lang}, {@code java.math.BigInteger} and the {@code java.security} / {@code javax.crypto} classes
 * measured ALLOW under the game's real loader — no {@code java.io} stream (DENIED in-game), no
 * {@code java.nio}, no reflection, no other {@code cc.starsector} class, and no non-constant string
 * concatenation (an indy concat would reference {@code java.lang.invoke}). Encoding is by hand: arrays +
 * {@code System.arraycopy}.
 *
 * <p>Failure law: the two methods fed NETWORK bytes ({@link #scalarMult}, {@link #open}) never throw on them —
 * they return {@code null}, and that null IS the count hook: this class neither logs nor meters (it rides the
 * client jar), the CALLER counts. A caller bug (a null argument elsewhere, a wrong key / nonce length to
 * {@link #seal}) throws {@code IllegalArgumentException} naming the parameter; a JDK algorithm that fails on the
 * pinned JVMs (a broken install) throws {@code IllegalStateException} whose message is the algorithm name only,
 * with no cause chained. No message here ever carries key, scalar, password or plaintext bytes.
 *
 *
 */
public final class HandshakeCrypto {

    public static final int POINT_BYTES = 32, SCALAR_BYTES = 32, ISK_BYTES = 64, KEY_BYTES = 32, NONCE_BYTES = 12, TAG_BYTES = 16;

    /** SHA-512's input block = the draft's {@code H.s_in_bytes} (section 8.1 generator_string zero padding; B.1.1 "128 bytes"). */
    private static final int SHA512_BLOCK_BYTES = 128;
    /** HMAC-SHA256 output = RFC 5869's HashLen (the default salt length and the expand block). */
    private static final int HMAC_SHA256_BYTES = 32;
    private static final byte[] EMPTY = new byte[0];
    /** Draft section 8.2: {@code G_X25519.DSI = b"CPace255"}. */
    private static final byte[] DSI = ascii("CPace255");
    /** Draft section 7.2: {@code G.DSI || b"_ISK"} (B.1.5 prints it as {@code b'CPace255_ISK'}). */
    private static final byte[] DSI_ISK = ascii("CPace255_ISK");

    /** Curve25519's field: p = 2^255 - 19 (RFC 7748 section 4.1). */
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger P_MINUS_2 = P.subtract(BigInteger.TWO);
    /** Draft A.5 {@code powerForLegendreSymbol = floor((q-1)/2)}. */
    private static final BigInteger LEGENDRE_EXP = P.subtract(BigInteger.ONE).shiftRight(1);
    /** Curve25519's Montgomery A (RFC 7748 section 4.1; draft A.5's {@code A}; RFC 9380's {@code J}). */
    private static final BigInteger A = BigInteger.valueOf(486662);
    /** Draft A.5: the non-square {@code Z = 2} for Curve25519 (RFC 9380 section 8.5 suite constant {@code Z: 2}). */
    private static final BigInteger Z = BigInteger.TWO;
    /** {@code A/2} in GF(p), draft A.5's {@code (1 - epsilon) * A/2} term. */
    private static final BigInteger HALF_A = A.multiply(BigInteger.TWO.modPow(P_MINUS_2, P)).mod(P);

    private HandshakeCrypto() {
    }

    // --- the draft's string utilities -------------------------------------------------------------------------

    /** Draft A.1.1 {@code prepend_len}: the LEB128 length of {@code data}, then {@code data}. */
    static byte[] prependLen(byte[] data) {
        need(data, "data");
        byte[] out = new byte[lebSize(data.length) + data.length];
        int at = putLeb(out, 0, data.length);
        System.arraycopy(data, 0, out, at, data.length);
        return out;
    }

    /** Draft A.1.3 {@code lv_cat}: {@code prepend_len} of every part, in order (an empty part keeps its 0x00 byte). */
    static byte[] lvCat(byte[]... parts) {
        if (parts == null) {
            throw new IllegalArgumentException("parts");
        }
        int total = 0;
        for (byte[] part : parts) {
            need(part, "part");
            total += lebSize(part.length) + part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            at = putLeb(out, at, part.length);
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /** Plain concatenation, no length prefixes — the hand encoder later lanes reuse (no java.io stream rides B). */
    public static byte[] concat(byte[]... parts) {
        if (parts == null) {
            throw new IllegalArgumentException("parts");
        }
        int total = 0;
        for (byte[] part : parts) {
            need(part, "part");
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    // --- generator (draft sections 8.1 + 8.2) ------------------------------------------------------------------------

    /**
     * Draft section 8.1 / A.2 {@code generator_string(G.DSI, PRS, CI, sid, s_in_bytes = 128)} =
     * {@code lv_cat(DSI, PRS, zero_bytes(len_zpad), CI, sid)} with
     * {@code len_zpad = max(0, s_in_bytes - 1 - len(prepend_len(PRS)) - len(prepend_len(DSI)))}. An EMPTY sid is
     * still a present field (its single 0x00 length byte ends the string).
     */
    static byte[] generatorString(byte[] prs, byte[] ci, byte[] sid) {
        need(prs, "prs");
        need(ci, "ci");
        need(sid, "sid");
        int lenZpad = Math.max(0, SHA512_BLOCK_BYTES - 1
                - (lebSize(prs.length) + prs.length) - (lebSize(DSI.length) + DSI.length));
        return lvCat(DSI, prs, new byte[lenZpad], ci, sid);
    }

    /** Draft section 8.2 {@code gen_str_hash = H.hash(gen_str, 32)}: the FIRST 32 bytes of SHA-512 (draft section 6.2 H.hash(m,l)). */
    static byte[] generatorHash(byte[] prs, byte[] ci, byte[] sid) {
        byte[] genStr = generatorString(prs, ci, sid);
        byte[] full = sha512().digest(genStr);
        byte[] out = new byte[POINT_BYTES];
        System.arraycopy(full, 0, out, 0, POINT_BYTES);
        // The string carries the PRS verbatim and the digest is its hash — neither outlives the map
        // (by hand: java.util is outside this class's classloader-B list, class javadoc)
        for (int i = 0; i < genStr.length; i++) {
            genStr[i] = 0;
        }
        for (int i = 0; i < full.length; i++) {
            full[i] = 0;
        }
        return out;
    }

    /**
     * Draft A.4 / RFC 7748 {@code decodeUCoordinate(u, 255)}, returned in its little-endian encoding: bit 255
     * cleared, NOT reduced mod p (B.1.1 prints this as "decoded field element of 255 bits").
     */
    static byte[] decodeUCoordinate(byte[] u32) {
        if (u32 == null || u32.length != POINT_BYTES) {
            throw new IllegalArgumentException("u32");
        }
        byte[] out = u32.clone();
        out[POINT_BYTES - 1] &= 0x7f;
        return out;
    }

    /**
     * Draft A.5 {@code elligator2(r, p, A, 255)} with Z = 2: {@code v = -A / (1 + Z*r^2)},
     * {@code epsilon = (v^3 + A*v^2 + v)^((p-1)/2)}, {@code x = epsilon*v - (1 - epsilon)*A/2}, encoded little-endian
     * (= RFC 9380 section 6.7.1's x: x1 when gx1 is square, else -x1 - J). The input is an encoded field element (bit 255
     * masked, then reduced mod p); the inverse is {@code modPow(p - 2)}, so an inverse of 0 is 0 (RFC 9380
     * {@code inv0}; 1 + 2r^2 = 0 has no root here, RFC 9380 section 6.7.1 "can only happen when q = 3 (mod 4)").
     * NOT constant-time ({@code BigInteger}): a recorded deviation from draft section 10.10 "SHALL execute in constant
     * time" (the door maps once at serve open with no attacker trigger; a client join flight maps
     * once, in its constructor, before its first datagram, so no COOKIE13 times it ({@code Handshake13Flight})
     * — but HOW MANY flights are built is not rationed by the pair budget (it gates seals, never constructions): a
     * door that goes silent induces re-HELLO pump builds, each timed only against the timer's jitter;
     * and the variant check maps once per fetch inside its COOKIE13 answer and {@code /auth} once per proof: a
     * door CAN time those. With sid EMPTY and CI fixed every sample, however many, reads the same ONE password-fixed
     * branch: a bounded few-bit partition, never cumulative).
     */
    static byte[] elligator2(byte[] u32) {
        if (u32 == null || u32.length != POINT_BYTES) {
            throw new IllegalArgumentException("u32");
        }
        BigInteger r = fieldOf(u32).mod(P);
        BigInteger den = BigInteger.ONE.add(Z.multiply(r).multiply(r)).mod(P);
        BigInteger v = P.subtract(A).multiply(den.modPow(P_MINUS_2, P)).mod(P);
        BigInteger gv = v.multiply(v).add(A.multiply(v)).add(BigInteger.ONE).multiply(v).mod(P);
        BigInteger epsilon = gv.modPow(LEGENDRE_EXP, P);
        BigInteger x = epsilon.multiply(v).subtract(BigInteger.ONE.subtract(epsilon).multiply(HALF_A)).mod(P);
        return encodeU(x);
    }

    /**
     * Draft section 8.2 {@code G.calculate_generator(H, PRS, CI, sid)}: SHA-512 of the generator string, its first 32 B,
     * {@code decodeUCoordinate} (bit 255 masked), Elligator 2 — the u-coordinate only, NO cofactor clearing (the
     * draft's map output is used as is; X25519's clamped scalar absorbs the cofactor). The draft-vector entry point.
     */
    static byte[] generator(byte[] prs, byte[] ci, byte[] sid) {
        return elligator2(decodeUCoordinate(generatorHash(prs, ci, sid)));
    }

    /**
     * The PRODUCT generator: {@link #generator(byte[], byte[], byte[])} with {@code sid} EMPTY — a stated
     * deviation (draft section 10.9 RECOMMENDS a unique sid when the protocol can establish one).
     * {@code prs} = the typed secret's bytes, {@code ci} = the channel label the codec lane fixes.
     */
    public static byte[] generator(byte[] prs, byte[] ci) {
        return generator(prs, ci, EMPTY);
    }

    // --- scalars --------------------------------------------------------------------------------------------

    /** Draft section 8.2 {@code G_X25519.sample_scalar()} = 32 random bytes (X25519 clamps them; RFC 7748 section 5). */
    public static byte[] newScalar(SecureRandom rng) {
        if (rng == null) {
            throw new IllegalArgumentException("rng");
        }
        byte[] scalar = new byte[SCALAR_BYTES];
        rng.nextBytes(scalar);
        return scalar;
    }

    /**
     * Draft section 8.2 {@code G_X25519.scalar_mult(y, g) = scalar_mult_vfy(y, g) = X25519(y, g)} for an ARBITRARY u
     * (a password-derived generator or a peer's share), via the JDK's XDH ({@code KeyFactory} + {@code KeyAgreement}
     * "XDH", the call shape measured under the game's loader). {@code u} is
     * decoded per RFC 7748 (little-endian, bit 255 masked) before the JDK sees it.
     *
     * <p>{@code u} arrives from the NETWORK, so this never throws on it: returns {@code null} = REJECT when either
     * array is null or not 32 B; when the JDK throws {@code InvalidKeyException} (measured: "Point has
     * small order" for the small-order u values on all three JVMs — draft section 7.2 "MUST abort if K=G.I"); or when the
     * 32 B result is all zero (belt and braces, an OR over every byte, no early exit). This class does not count —
     * the CALLER counts every {@code null}. Any other {@code GeneralSecurityException} = a broken install:
     * {@code IllegalStateException("XDH")}.
     */
    public static byte[] scalarMult(byte[] scalar, byte[] u) {
        if (scalar == null || u == null || scalar.length != SCALAR_BYTES || u.length != POINT_BYTES) {
            return null;
        }
        byte[] secret;
        try {
            KeyFactory kf = KeyFactory.getInstance("XDH");
            PrivateKey priv = kf.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, scalar));
            PublicKey pub = kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, fieldOf(u)));
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(priv);
            ka.doPhase(pub, true);
            secret = ka.generateSecret();
        } catch (InvalidKeyException smallOrder) {
            return null;
        } catch (GeneralSecurityException brokenInstall) {
            throw new IllegalStateException("XDH");
        }
        if (secret == null || secret.length != POINT_BYTES) {
            throw new IllegalStateException("XDH");
        }
        int acc = 0;
        for (byte b : secret) {
            acc |= b;
        }
        return acc == 0 ? null : secret;
    }

    // --- ISK (draft section 7.2) -----------------------------------------------------------------------------------

    /** Draft A.3.4 {@code transcript_ir(Ya, ADa, Yb, ADb) = lv_cat(Ya, ADa) || lv_cat(Yb, ADb)} (initiator = A = the client). */
    static byte[] transcriptIr(byte[] ya, byte[] ada, byte[] yb, byte[] adb) {
        return concat(lvCat(ya, ada), lvCat(yb, adb));
    }

    /** Draft section 7.2 / B.1.5 hash input {@code lv_cat(G.DSI || b"_ISK", sid, K) || transcript_ir(Ya, ADa, Yb, ADb)}. */
    static byte[] iskInput(byte[] sid, byte[] k, byte[] ya, byte[] ada, byte[] yb, byte[] adb) {
        return concat(lvCat(DSI_ISK, sid, k), transcriptIr(ya, ada, yb, adb));
    }

    /**
     * Draft section 7.2 {@code ISK = H.hash(lv_cat(G.DSI || b"_ISK", sid, K) || transcript_ir(Ya, ADa, Yb, ADb))}, SHA-512,
     * the full 64 B. {@code k} must be a {@link #scalarMult} result that was NOT {@code null} (the K = G.I abort
     * happens there). The product passes an EMPTY sid.
     */
    public static byte[] isk(byte[] sid, byte[] k, byte[] ya, byte[] ada, byte[] yb, byte[] adb) {
        return sha512().digest(iskInput(sid, k, ya, ada, yb, adb));
    }

    // --- KDF + AEAD -----------------------------------------------------------------------------------------

    /**
     * RFC 5869 HKDF with HMAC-SHA256 ({@code javax.crypto.Mac "HmacSHA256"}): Extract {@code PRK = HMAC(salt, ikm)},
     * then Expand {@code T(i) = HMAC(PRK, T(i-1) || info || i)} to {@code outLen} bytes. A {@code null} or empty
     * salt = "not provided" = HashLen zeros (RFC 5869 section 2.2). {@code outLen} in [1, 255 * 32] (section 2.3), else
     * {@code IllegalArgumentException}.
     */
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int outLen) {
        need(ikm, "ikm");
        need(info, "info");
        if (outLen < 1 || outLen > 255 * HMAC_SHA256_BYTES) {
            throw new IllegalArgumentException("outLen");
        }
        byte[] extractKey = (salt == null || salt.length == 0) ? new byte[HMAC_SHA256_BYTES] : salt;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(extractKey, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] okm = new byte[outLen];
            byte[] t = EMPTY;
            int at = 0;
            for (int i = 1; at < outLen; i++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                int n = Math.min(t.length, outLen - at);
                System.arraycopy(t, 0, okm, at, n);
                at += n;
            }
            return okm;
        } catch (GeneralSecurityException brokenInstall) {
            throw new IllegalStateException("HmacSHA256");
        }
    }

    /**
     * RFC 8439 section 2.8 AEAD_CHACHA20_POLY1305 ({@code Cipher "ChaCha20-Poly1305"}, a fresh instance per call): returns
     * ciphertext || 16 B tag. Every argument is the CALLER's (never network bytes), so a null or a wrong key / nonce
     * length is a caller bug = {@code IllegalArgumentException}. A key + nonce pair must never seal twice with
     * different plaintexts (every wrap key and {@code ck} is single-use).
     */
    public static byte[] seal(byte[] key, byte[] nonce12, byte[] aad, byte[] plain) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("key");
        }
        if (nonce12 == null || nonce12.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce12");
        }
        need(aad, "aad");
        need(plain, "plain");
        try {
            return chacha(Cipher.ENCRYPT_MODE, key, nonce12, aad, plain);
        } catch (GeneralSecurityException brokenInstall) {
            throw new IllegalStateException("ChaCha20-Poly1305");
        }
    }

    /**
     * The inverse of {@link #seal}. {@code sealed} (and the AAD it is bound to) arrives from the NETWORK, so this
     * never throws on it: returns {@code null} = REJECT on a bad tag ({@code AEADBadTagException}: a flipped
     * ciphertext / tag / AAD byte, a wrong key or nonce), an input shorter than the 16 B tag, a null argument, or a
     * wrong key / nonce length. This class does not count — the CALLER counts every {@code null}. Any other
     * {@code GeneralSecurityException} = a broken install: {@code IllegalStateException("ChaCha20-Poly1305")}.
     */
    public static byte[] open(byte[] key, byte[] nonce12, byte[] aad, byte[] sealed) {
        if (key == null || key.length != KEY_BYTES || nonce12 == null || nonce12.length != NONCE_BYTES
                || aad == null || sealed == null || sealed.length < TAG_BYTES) {
            return null;
        }
        try {
            return chacha(Cipher.DECRYPT_MODE, key, nonce12, aad, sealed);
        } catch (AEADBadTagException badTag) {
            return null;
        } catch (GeneralSecurityException brokenInstall) {
            throw new IllegalStateException("ChaCha20-Poly1305");
        }
    }

    /**
     * Byte-array equality whose time does not depend on WHERE the arrays differ ({@code MessageDigest.isEqual}) —
     * for tags, tickets and MACs. Either side {@code null} = {@code false} (also null vs
     * null: two failed computations never compare equal); different lengths = {@code false}.
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    /**
     * ONE throwaway run of generator + 4 x scalarMult + isk + hkdf + seal/open on fixed public inputs (no secret),
     * so whoever calls it pays the JDK providers' first-execution cost (measured: 37-92 ms for XDH) OFF the
     * reader / frame thread. It also checks its own run: both sides agree on K and the AEAD round trip
     * returns the plaintext, else {@code IllegalStateException} naming the algorithm — a provider broken in a way
     * that only ever REJECTS ({@code scalarMult}'s {@code InvalidKeyException} branch) surfaces here, at warm time,
     * instead of as every join's counted reject.
     */
    public static void warm() {
        byte[] label = ascii("hs13-warm");
        byte[] g = generator(label, label);
        byte[] a = new byte[SCALAR_BYTES];
        byte[] b = new byte[SCALAR_BYTES];
        for (int i = 0; i < SCALAR_BYTES; i++) {
            a[i] = (byte) (i + 1);
            b[i] = (byte) (0x40 + i);
        }
        byte[] ya = scalarMult(a, g);
        byte[] yb = scalarMult(b, g);
        byte[] ka = ya == null || yb == null ? null : scalarMult(a, yb);
        byte[] kb = ya == null || yb == null ? null : scalarMult(b, ya);
        if (ka == null || !constantTimeEquals(ka, kb)) {
            throw new IllegalStateException("XDH");
        }
        byte[] key = hkdf(isk(EMPTY, ka, ya, label, yb, label), null, label, KEY_BYTES);
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] opened = open(key, nonce, label, seal(key, nonce, label, label));
        if (!constantTimeEquals(opened, label)) {
            throw new IllegalStateException("ChaCha20-Poly1305");
        }
    }

    // --- private helpers ------------------------------------------------------------------------------------

    private static byte[] chacha(int mode, byte[] key, byte[] nonce12, byte[] aad, byte[] in)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce12));
        c.updateAAD(aad);
        return c.doFinal(in);
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException brokenInstall) {
            throw new IllegalStateException("SHA-512");
        }
    }

    /** RFC 7748 decodeUCoordinate as an integer: little-endian, bit 255 masked (not reduced mod p). */
    private static BigInteger fieldOf(byte[] le) {
        byte[] be = new byte[POINT_BYTES];
        for (int i = 0; i < POINT_BYTES; i++) {
            be[i] = le[POINT_BYTES - 1 - i];
        }
        be[0] &= 0x7f;
        return new BigInteger(1, be);
    }

    /** RFC 7748 encodeUCoordinate for 0 <= x < p: 32 B little-endian. */
    private static byte[] encodeU(BigInteger x) {
        byte[] be = x.toByteArray();
        byte[] le = new byte[POINT_BYTES];
        for (int i = 0; i < be.length && i < POINT_BYTES; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    private static int lebSize(int len) {
        int n = 1;
        for (int v = len >>> 7; v != 0; v >>>= 7) {
            n++;
        }
        return n;
    }

    /** Draft A.1.1's loop: 7 bits per byte, least significant first, bit 7 set while more bytes follow. */
    private static int putLeb(byte[] out, int at, int len) {
        int v = len;
        while (true) {
            out[at++] = (byte) (v < 128 ? v : (v & 0x7f) | 0x80);
            v >>>= 7;
            if (v == 0) {
                return at;
            }
        }
    }

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) s.charAt(i);
        }
        return out;
    }

    private static void need(byte[] value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name);
        }
    }
}
