package cc.starsector.server.runtime;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The hs-13 in-match {@code /auth} proof.
 * WHY: on a version-12 session the chat line
 * {@code /auth <admin password>} carries the admin secret READABLE to anyone on the path; on a version-13 session the
 * kit sends instead {@code /auth } + 104 lowercase hex = {@code Ya″[32] ‖ u32 ctr ‖ tag[16]} (110 chars, inside the
 * chat cap; no new frame), a one-shot CPace-style key confirmation against the door's per-admit auth share
 * {@code YbAuth = b″·G_a} that rode the sealed WELCOME13. One online guess per proof; no offline test for a reader
 * (the tag needs {@code a″} or {@code b″}); bound to the session root, so a proof made in one session fails in any
 * other. The one exception: a match-password holder in the path at the HELLO can substitute
 * {@code YbAuth}, so it tests ONE admin-secret guess OFFLINE per proof it receives, and each forced re-admission's
 * {@code AuthReplayLaw} re-send yields one more, counted by no door limit.
 *
 * <p>The exchange: {@code sid″ = hkdf(sessionRoot, "cc-hs13 auth sid", 32) ‖ u32 ctr};
 * {@code ISK″ = isk(sid″, K″, Ya″, 'A', YbAuth, EMPTY)}; {@code tag = hkdf(ISK″, "cc-hs13 auth c2s", 16)} — an HKDF
 * tag, not the CPace draft's suggested MAC recipe. The client half is
 * {@link #proveOrNull}, the door half {@link #parseOrNull} + {@link #verify}. Pure: no state, no logging, no I/O.
 *
 * <p><b>Failure law</b> (the {@link HandshakeCrypto} convention): network input never throws — {@link #parseOrNull}
 * returns {@code null} on any malformed argument; {@link #verify} returns {@code false} when {@code Ya″} is refused
 * by {@code scalarMult} (all-zero / small-order) or the tag differs; {@link #proveOrNull} returns {@code null} when
 * {@code YbAuth} is refused or the secret is empty / blank. A caller bug (a null or wrong-length root, scalar or
 * door share, a counter outside 1..2³²−1, a null rng / proof) = {@code IllegalArgumentException} naming the
 * parameter only. No exception text, {@code toString} or javadoc example carries a secret, share, scalar, root, tag
 * or counter value.
 *
 * <p><b>VALUE-DOMAIN.</b> {@code counter} = an unsigned 32-bit value carried in a {@code long}, 1..2³²−1 (0 is
 * refused: the door's "none granted yet" mark); every root / share / scalar is 32 B.
 *
 * <p><b>Classloader B</b> (lane iv2 folds this class into the client jar): it references only
 * {@code java.lang}, {@code java.util.Arrays}, {@code java.security.SecureRandom} and the project classes
 * {@link Handshake13} / {@link HandshakeCrypto}. Hex is encoded and decoded by a {@code char} loop (no
 * {@code String.format}, no {@code HexFormat}, no charset); there is no non-constant string concatenation (the line is
 * built in a {@code char[]}), and {@link Proof} overrides {@code equals} / {@code hashCode} / {@code toString}
 * (constant text).
 *
 *
 */
public final class Handshake13Auth {

    /** {@code Ya″[32] ‖ u32 ctr ‖ tag[16]}. */
    public static final int PROOF_BYTES = 52, PROOF_HEX_CHARS = 2 * PROOF_BYTES, TAG_BYTES = 16;
    /** The highest counter (u32); the lowest legal one is 1. */
    public static final long COUNTER_MAX = 0xFFFF_FFFFL;

    private static final int POINT = HandshakeCrypto.POINT_BYTES, SCALAR = HandshakeCrypto.SCALAR_BYTES,
            ROOT = HandshakeCrypto.KEY_BYTES;
    private static final int CTR_AT = POINT, TAG_AT = POINT + 4, SID_ROOT_BYTES = 32, SID_BYTES = SID_ROOT_BYTES + 4;
    /** {@code ADa″}: ASCII {@code A} (HELLO uses {@code H}, VCHK {@code V}); {@code ADb″} is EMPTY. */
    private static final byte AD_PURPOSE_AUTH = 0x41;
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] LABEL_SID = ascii("cc-hs13 auth sid");
    /** The one direction this proof flows; {@code cc-hs13 auth s2c} is reserved, unbuilt. */
    private static final byte[] LABEL_C2S = ascii("cc-hs13 auth c2s");
    private static final char[] PREFIX = {'/', 'a', 'u', 't', 'h', ' '};
    private static final char[] HEX = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    private Handshake13Auth() {
    }

    /** One parsed proof: {@code Ya″}, the counter (1..2³²−1) and the tag. */
    public static final class Proof {
        private final byte[] ya, tag;
        private final long counter;

        private Proof(byte[] ya, long counter, byte[] tag) {
            this.ya = ya;
            this.counter = counter;
            this.tag = tag;
        }

        /** The u32 counter, 1..2³²−1 (never 0: {@link #parseOrNull} refuses it). */
        public long counter() {
            return counter;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Proof other && counter == other.counter && Arrays.equals(ya, other.ya)
                    && Arrays.equals(tag, other.tag);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Long.hashCode(counter) + Arrays.hashCode(ya)) + Arrays.hashCode(tag);
        }

        /** Constant text: never the share, the tag or the counter. */
        @Override
        public String toString() {
            return "Handshake13Auth.Proof";
        }
    }

    /**
     * The client's half: the whole chat line {@code "/auth " + 104 lowercase hex} for {@code counter}, or {@code null}
     * when {@code secret} is null / blank ({@link Handshake13#secretBytes} is empty — nothing to prove) or
     * {@code scalarMult} refuses {@code ybAuth} (null, not 32 B, all-zero or small-order).
     * {@code G_a = generator(secretBytes(secret), CI)} is the door's own text-to-bytes and channel label; the scalar,
     * {@code K″}, {@code ISK″}, {@code G_a} and the secret's byte copy are zeroed before return. Cost: the map + 2
     * X25519 — off the frame thread (the kit's chat drain). {@code IllegalArgumentException}: a null / non-32 B
     * {@code sessionRoot}, a counter outside 1..2³²−1, a null {@code rng}.
     */
    public static String proveOrNull(String secret, byte[] sessionRoot, byte[] ybAuth, long counter, SecureRandom rng) {
        requireLen(sessionRoot, ROOT, "sessionRoot");
        requireCounter(counter);
        if (rng == null) {
            throw new IllegalArgumentException("rng");
        }
        byte[] prs = Handshake13.secretBytes(secret);
        if (prs.length == 0) {
            return null;
        }
        byte[] generator = HandshakeCrypto.generator(prs, Handshake13.ci());
        Arrays.fill(prs, (byte) 0);
        byte[] a = HandshakeCrypto.newScalar(rng);
        byte[] ya = HandshakeCrypto.scalarMult(a, generator);
        byte[] k = ya == null ? null : HandshakeCrypto.scalarMult(a, ybAuth);
        Arrays.fill(a, (byte) 0);
        Arrays.fill(generator, (byte) 0);
        if (k == null) {
            return null;
        }
        byte[] tag = tag(sessionRoot, counter, k, ya, ybAuth);
        Arrays.fill(k, (byte) 0);
        byte[] proof = new byte[PROOF_BYTES];
        System.arraycopy(ya, 0, proof, 0, POINT);
        putU32(proof, CTR_AT, counter);
        System.arraycopy(tag, 0, proof, TAG_AT, TAG_BYTES);
        char[] line = new char[PREFIX.length + PROOF_HEX_CHARS];
        System.arraycopy(PREFIX, 0, line, 0, PREFIX.length);
        for (int i = 0; i < PROOF_BYTES; i++) {
            line[PREFIX.length + 2 * i] = HEX[(proof[i] >> 4) & 0xF];
            line[PREFIX.length + 2 * i + 1] = HEX[proof[i] & 0xF];
        }
        return new String(line);
    }

    /**
     * The door's parse (no crypto, no lock): the {@code /auth} ARGUMENT (the text after {@code "/auth "}) → a
     * {@link Proof}, or {@code null} unless it is EXACTLY 104 chars, every one in {@code [0-9a-f]} (uppercase refused:
     * one canonical form), and the counter is not 0. A readable password fails here (unless it is itself 104
     * lowercase hex — then it parses as a proof and fails {@link #verify}); it is never compared as a password.
     */
    public static Proof parseOrNull(String arg) {
        if (arg == null || arg.length() != PROOF_HEX_CHARS) {
            return null;
        }
        byte[] raw = new byte[PROOF_BYTES];
        for (int i = 0; i < PROOF_BYTES; i++) {
            int hi = nibbleOrNegative(arg.charAt(2 * i));
            int lo = nibbleOrNegative(arg.charAt(2 * i + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            raw[i] = (byte) ((hi << 4) | lo);
        }
        long counter = ((raw[CTR_AT] & 0xFFL) << 24) | ((raw[CTR_AT + 1] & 0xFFL) << 16)
                | ((raw[CTR_AT + 2] & 0xFFL) << 8) | (raw[CTR_AT + 3] & 0xFFL);
        if (counter == 0L) {
            return null;
        }
        return new Proof(Arrays.copyOf(raw, POINT), counter, Arrays.copyOfRange(raw, TAG_AT, PROOF_BYTES));
    }

    /**
     * The door's verify (ONE X25519 + {@code isk} + {@code hkdf} + a constant-time compare; never under a lock):
     * {@code K″ = b″·Ya″} — {@code false} when {@code scalarMult} refuses {@code Ya″} (all-zero / small-order, no
     * throw) — then the expected tag under THIS session's root and the proof's counter. A door with no admin secret
     * armed holds a throwaway {@code G_a}, so its share never verifies — the same work, the same answer.
     * {@code IllegalArgumentException} (a caller bug: the door's own values): a null / non-32 B {@code sessionRoot},
     * {@code authScalar} or {@code ybAuth}, a null {@code proof}.
     */
    public static boolean verify(byte[] sessionRoot, byte[] authScalar, byte[] ybAuth, Proof proof) {
        requireLen(sessionRoot, ROOT, "sessionRoot");
        requireLen(authScalar, SCALAR, "authScalar");
        requireLen(ybAuth, POINT, "ybAuth");
        if (proof == null) {
            throw new IllegalArgumentException("proof");
        }
        byte[] k = HandshakeCrypto.scalarMult(authScalar, proof.ya);
        if (k == null) {
            return false;
        }
        byte[] expected = tag(sessionRoot, proof.counter, k, proof.ya, ybAuth);
        Arrays.fill(k, (byte) 0);
        return HandshakeCrypto.constantTimeEquals(expected, proof.tag);
    }

    // --- internals ---------------------------------------------------------------------------------------------------

    /** {@code hkdf(ISK″, "cc-hs13 auth c2s", 16)}, {@code ISK″ = isk(sid″, K″, Ya″, 'A', YbAuth, EMPTY)}; ISK zeroed. */
    private static byte[] tag(byte[] sessionRoot, long counter, byte[] k, byte[] ya, byte[] ybAuth) {
        byte[] isk = HandshakeCrypto.isk(sid(sessionRoot, counter), k, ya, new byte[] {AD_PURPOSE_AUTH}, ybAuth, EMPTY);
        byte[] tag = HandshakeCrypto.hkdf(isk, null, LABEL_C2S, TAG_BYTES);
        Arrays.fill(isk, (byte) 0);
        return tag;
    }

    /** {@code sid″ = hkdf(sessionRoot, null, "cc-hs13 auth sid", 32) ‖ u32 counter} (36 B, big-endian counter). */
    private static byte[] sid(byte[] sessionRoot, long counter) {
        byte[] sid = Arrays.copyOf(HandshakeCrypto.hkdf(sessionRoot, null, LABEL_SID, SID_ROOT_BYTES), SID_BYTES);
        putU32(sid, SID_ROOT_BYTES, counter);
        return sid;
    }

    private static void putU32(byte[] out, int at, long value) {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
    }

    private static int nibbleOrNegative(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }

    private static void requireCounter(long counter) {
        if (counter < 1L || counter > COUNTER_MAX) {
            throw new IllegalArgumentException("counter");
        }
    }

    private static void requireLen(byte[] value, int len, String name) {
        if (value == null || value.length != len) {
            throw new IllegalArgumentException(name);
        }
    }

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) s.charAt(i);
        }
        return out;
    }
}
