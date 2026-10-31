package cc.starsector.server.runtime;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The hs-13 join handshake's wire codec + envelope.
 * WHY: on hs 12 the match password / admin secret crosses the network readable inside HELLO; hs 13 sends a
 * CPace share instead ({@link HandshakeCrypto}), and the secret only decides WHICH derived key opens the client's
 * wrapped content key {@code ck}. This class writes the four message shapes — F1 {@code HELLO13-OPEN},
 * {@code COOKIE13} (d), F2 {@code HELLO13-SEAL} (e) and {@code WELCOME13} ADMITTED (g) — plus the key schedule both
 * ends run (f). REJECT stays the plaintext grammar at version byte {@link #VERSION} (h) and is NOT written here
 * ({@link HandshakeCodec.WelcomePayload#encodeRejected}). Since THE FLIP this is the ONLY admit path and
 * {@link HandshakeCodec#HANDSHAKE_VERSION} == {@link #VERSION}.
 *
 * <p><b>Failure law.</b> Network input never throws: every {@code decode…OrNull} / {@code parse…OrNull} /
 * {@code open…OrNull} returns {@code null} (or, for {@link #openHelloOrNull}, an {@link Opened} whose
 * {@code bodyOk} is false) on ANY malformed, truncated, over-long, wrong-version or failed-tag input. That null IS
 * the count hook: this class is pure and rides the client jar, so it neither logs nor meters — the CALLER counts.
 * Encoders / sealers take the CALLER's own values, so a breach there is a caller bug =
 * {@code IllegalArgumentException} whose message names the FIELD only, never its bytes. A JDK algorithm missing on a
 * pinned JVM = {@code IllegalStateException} naming the algorithm (the {@link HandshakeCrypto} convention).
 *
 * <p><b>No secret in any message.</b> No exception text, {@code toString} or javadoc example here carries a key,
 * scalar, share, PRS, mix or plaintext; the four records and four holders override {@code toString} with constant text.
 *
 * <p><b>Classloader B</b> (this class will be folded into the client jar): it references only
 * {@code java.lang}, {@code java.util.Arrays}, {@code java.io.IOException} (the reader's),
 * {@code java.security.{SecureRandom, GeneralSecurityException}}, {@code javax.crypto.Mac},
 * {@code javax.crypto.spec.SecretKeySpec} and the project classes {@link DatagramFrameCodec}, {@link HandshakeCodec},
 * {@link HandshakeCrypto}. A-strings are encoded by a {@code char} loop (no {@code java.nio.charset}); there is no
 * non-constant string concatenation and every record overrides {@code equals} / {@code hashCode} / {@code toString}
 * (so no {@code invokedynamic} bootstrap rides the class); the serve-schema byte is a compile-time constant, inlined
 * (javac leaves an orphan, never-resolved pool entry for its owner {@code AuthoritativeWorldSnapshotCodec}, exactly as
 * in {@code HandshakeCodec.WelcomePayload} — no instruction names it).
 *
 * <p><b>VALUE-DOMAIN.</b> {@code nonce} / {@code nonceEcho} / {@code sessionId} = any signed long; {@code desiredShipId}
 * and {@code boundShipId} are {@code >= 0} on the wire; {@code startingRoundId} 0..255; {@code tickRate} 1..65535;
 * {@code serverLife != 0}; {@code seq >= 0}; {@code mixIndex} = an index into the door's {@code mixesM}, or -1 for
 * {@link #KIND_ADMIN}.
 *
 *
 */
public final class Handshake13 {

    /**
     * The sealed handshake's wire version: 14 = the version-13 envelope with a {@code u8 team} closing the F2 body
     * (0 = none, the door picks; 1 = side 0; 2 = side 1). The key-schedule LABELS keep their {@code hs13} names; the
     * byte enters ADa and every message head, so a version-13 kit meets the door's {@code kit_outdated} rung.
     *
     * 15 = the version-14 bytes unchanged; the bump gates the PINT ability bits 13..20, which a version-14 serve
     * refuses at decode (it would drop an ability-sending kit as corrupt at its first press, so the version byte
     * turns that into a readable {@code kit_outdated} at HELLO instead).
     * 16 = the version-15 bytes unchanged; the bump gates PINT schema 7 (the pilot's raw input levels, 74 B frames),
     * whose 63 B schema-6 frame a version-16 serve refuses at decode, so a version-15 kit meets {@code kit_outdated}
     * at HELLO instead of a drop at its first frame.
     * In place at 16 (unshipped): + the pilot's 16-byte control keymap closing the sealed F2 body.
     *
     * A bump re-mints the {@code Handshake13KatRows} K0..K7 goldens in the SAME commit (two temporary hex-print
     * passes, never committed): the byte heads every frozen message, so the 15 and 16 bumps both went red on it.
     *
     */
    public static final int VERSION = 16;
    /** The highest lawful {@code team} byte; a sealed 3..255 opens as {@code bodyOk == false}. */
    public static final int TEAM_MAX = 2;
    /** The sealed F2 body's trailing pilot keymap: one byte per {@link PilotIntentFrame#CONTROL_KEY_NAMES} entry. */
    public static final int KEYMAP_BYTES = 16;
    public static final int FLAG_PROBE = HandshakeCodec.HELLO_FLAG_PROBE, FLAG_OBSERVER = HandshakeCodec.HELLO_FLAG_OBSERVER,
            FLAG_NO_SECRET = 0x04;
    /**
     * {@code 0x08} = this game reports its MagicLib subsystem states: a REPORT the sender makes about its own game, set
     * only after the kit's guarded MagicLib probe passed. The serve folds it into the seat's FOLLOW flag on the hull
     * mark; a false bit hands the liar's OWN hull the pilot's held bits and reaches no other seat.
     * {@code 0x10} = this game's MagicLib subsystem class was patched: a report only (the host's view of a
     * client-side disarm), no serve behaviour keys on it. Both folded into hs 15 in place (no kit released before
     * them sent 15) and stay defined since; bits {@code 0x20..0x80} stay undefined and every decoder refuses them.
     *
     */
    public static final int FLAG_MAGIC_UP = 0x08, FLAG_MAGIC_REPLICA = 0x10;
    public static final int FLAGS_DEFINED = FLAG_PROBE | FLAG_OBSERVER | FLAG_NO_SECRET | FLAG_MAGIC_UP
            | FLAG_MAGIC_REPLICA;
    /** The bits a CALLER may hand the F2 sealer and the flight: every defined bit but {@link #FLAG_NO_SECRET},
     *  which only the sealer sets (from an empty secret). */
    public static final int CALLER_FLAGS = FLAGS_DEFINED & ~FLAG_NO_SECRET;
    public static final int COOKIE_MAX_BYTES = HandshakeCodec.HELLO_COOKIE_MAX_BYTES, SALT_BYTES = 16, HANDLE_BYTES = 16,
            WRAP_BYTES = 48;
    public static final int CID_MAX = 64, PRINT_MAX = 128, VARIANT_MAX = 64;
    public static final int OPEN_PAYLOAD_BYTES = 123, SEAL_PAYLOAD_MAX_BYTES = 487, ADMITTED_PLAIN_BYTES = 65;
    public static final int KIND_MATCH = 'm', KIND_ADMIN = 'a';

    private static final int STATUS_ADMITTED = HandshakeCodec.WELCOME_ADMITTED;
    private static final int STATUS_COOKIE = HandshakeCodec.WELCOME_COOKIE;
    private static final int STATUS_MAX = 2;
    private static final int POINT = HandshakeCrypto.POINT_BYTES, KEY = HandshakeCrypto.KEY_BYTES,
            TAG = HandshakeCrypto.TAG_BYTES, NONCE = HandshakeCrypto.NONCE_BYTES, ISK = HandshakeCrypto.ISK_BYTES;
    /** F1 bytes 0..10 = {@code ver ‖ nonce ‖ flags ‖ 0} — the FROZEN head. */
    private static final int OPEN_HEAD_BYTES = 11;
    /** COOKIE13 / ADb fixed tail after the cookie: {@code YbM ‖ YbA ‖ laneSalt}. */
    private static final int COOKIE_TAIL_BYTES = 2 * 32 + 16;
    /** F2 body plain = {@code A-string(64) ‖ u64 ‖ A-string(64) ‖ u8 team ‖ keymap[16]}: 27..155 B.
     * */
    private static final int BODY_MIN_BYTES = 1 + 8 + 1 + 1 + KEYMAP_BYTES,
            BODY_MAX_BYTES = 1 + CID_MAX + 8 + 1 + VARIANT_MAX + 1 + KEYMAP_BYTES;
    /** F2 at the 1 B cookie and every string empty (the low end of F2's 200..487 B).
     * */
    private static final int SEAL_PAYLOAD_MIN_BYTES = 200;
    /** ADa's purpose byte: ASCII {@code H} (VCHK uses {@code V}, lane iii). */
    private static final byte AD_PURPOSE_HELLO = 0x48;
    /** WELCOME13: {@code u8 VERSION ‖ u8 0 ‖ u64 nonceEcho ‖ doorSalt[16]} = the AAD, then {@code sealed[81]}. */
    private static final int WELCOME_AAD_BYTES = 1 + 1 + 8 + 16;
    private static final int WELCOME_PAYLOAD_BYTES = WELCOME_AAD_BYTES + ADMITTED_PLAIN_BYTES + 16;

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] CI = ascii("cc-starsector-hs13");
    private static final byte[] LABEL_WRAP_M = ascii("cc-hs13 wrap m");
    private static final byte[] LABEL_WRAP_A = ascii("cc-hs13 wrap a");
    private static final byte[] LABEL_BODY_C2S = ascii("cc-hs13 body c2s");
    private static final byte[] LABEL_WELCOME = ascii("cc-hs13 welcome");
    private static final byte[] LABEL_SESSION = ascii("cc-hs13 session");

    private Handshake13() {
    }

    /** {@code CI} — a fresh copy of the 18 ASCII bytes {@code cc-starsector-hs13} (no {@code match.id}). */
    public static byte[] ci() {
        return Arrays.copyOf(CI, CI.length);
    }

    // --- F1 HELLO13-OPEN --------------------------------------------------------------------------

    /** One decoded F1: the client's nonce and its flags (any subset of {@link #FLAGS_DEFINED}). */
    public record Open(long nonce, int flags) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Open other && nonce == other.nonce && flags == other.flags;
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(nonce) + flags;
        }

        @Override
        public String toString() {
            return "Handshake13.Open";
        }
    }

    /**
     * F1 {@code u8 VERSION ‖ u64 nonce ‖ u8 flags ‖ u8 0 ‖ pad} as the PAYLOAD (the caller frames it on
     * {@code WireChannel.HELLO}, as {@code HelloPayload.encode} does) — exactly {@value #OPEN_PAYLOAD_BYTES} B: the
     * pad is 112 zero bytes so the framed F1 (159 B) is never smaller than the COOKIE13 it earns (the 32 B cookie
     * cap). Bytes 0..10 are FROZEN across versions ({@code [10] == 0} = "an OPEN, no credential follows").
     */
    public static byte[] encodeOpen(long nonce, int flags) {
        if ((flags & ~FLAGS_DEFINED) != 0) {
            throw new IllegalArgumentException("flags");
        }
        byte[] payload = new byte[OPEN_PAYLOAD_BYTES];
        payload[0] = (byte) VERSION;
        DatagramFrameCodec.putU64(payload, 1, nonce);
        payload[9] = (byte) flags;
        return payload;
    }

    /**
     * F1 decode: length 11..{@link DatagramFrameCodec#MAX_PAYLOAD_BYTES}, {@code [0] == VERSION},
     * {@code [10] == 0}, flags defined, every pad byte 0 — else {@code null}. Any length {@code >= 11} is accepted
     * (only the encoder fixes 123 B); the 1:1 send-site floor stays the door's.
     */
    public static Open decodeOpenOrNull(byte[] payload) {
        if (payload == null || payload.length < OPEN_HEAD_BYTES || payload.length > DatagramFrameCodec.MAX_PAYLOAD_BYTES) {
            return null;
        }
        int flags = payload[9] & 0xFF;
        if ((payload[0] & 0xFF) != VERSION || payload[10] != 0 || (flags & ~FLAGS_DEFINED) != 0) {
            return null;
        }
        int pad = 0;
        for (int i = OPEN_HEAD_BYTES; i < payload.length; i++) {
            pad |= payload[i];
        }
        return pad != 0 ? null : new Open(u64(payload, 1), flags);
    }

    // --- COOKIE13 ---------------------------------------------------------------------------------

    /** One decoded COOKIE13: the nonce echo, the opaque cookie (1..32 B), the door's two shares and its lane salt. */
    public record Cookie13(long nonceEcho, byte[] cookie, byte[] ybM, byte[] ybA, byte[] laneSalt) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Cookie13 other && nonceEcho == other.nonceEcho && Arrays.equals(cookie, other.cookie)
                    && Arrays.equals(ybM, other.ybM) && Arrays.equals(ybA, other.ybA)
                    && Arrays.equals(laneSalt, other.laneSalt);
        }

        @Override
        public int hashCode() {
            int h = Long.hashCode(nonceEcho);
            h = 31 * h + Arrays.hashCode(cookie);
            h = 31 * h + Arrays.hashCode(ybM);
            h = 31 * h + Arrays.hashCode(ybA);
            return 31 * h + Arrays.hashCode(laneSalt);
        }

        @Override
        public String toString() {
            return "Handshake13.Cookie13";
        }
    }

    /**
     * {@code u8 VERSION ‖ u8 2 ‖ u64 nonceEcho ‖ u8 cookieLen ‖ cookie ‖ YbM[32] ‖ YbA[32] ‖ laneSalt[16]}, FRAMED on
     * {@code WireChannel.WELCOME} (as {@code WelcomePayload.encodeCookie} does) — 144 B at the door's 17 B cookie.
     * Bytes 10.. are {@link #adb} verbatim, so the door's recomputed ADb cannot drift from what it sent.
     * {@code IllegalArgumentException} on a cookie outside 1..32 B, a share not 32 B, a salt not 16 B.
     */
    public static byte[] encodeCookie(long nonceEcho, byte[] cookie, byte[] ybM, byte[] ybA, byte[] laneSalt) {
        byte[] tail = adb(cookie, ybM, ybA, laneSalt);
        byte[] payload = new byte[10 + tail.length];
        payload[0] = (byte) VERSION;
        payload[1] = (byte) STATUS_COOKIE;
        DatagramFrameCodec.putU64(payload, 2, nonceEcho);
        System.arraycopy(tail, 0, payload, 10, tail.length);
        return frame(DatagramFrameCodec.WireChannel.WELCOME, payload);
    }

    /** COOKIE13 decode of a WELCOME payload (the frame already stripped): {@code null} unless it is exactly that shape. */
    public static Cookie13 decodeCookieOrNull(byte[] payload) {
        if (payload == null) {
            return null;
        }
        try {
            DatagramFrameCodec.PayloadReader in = new DatagramFrameCodec.PayloadReader(payload);
            if (in.readU8() != VERSION || in.readU8() != STATUS_COOKIE) {
                return null;
            }
            long nonceEcho = in.readU64();
            int cookieLen = in.readU8();
            if (cookieLen < 1 || cookieLen > COOKIE_MAX_BYTES) {
                return null;
            }
            byte[] cookie = in.readBytes(cookieLen);
            byte[] ybM = in.readBytes(POINT);
            byte[] ybA = in.readBytes(POINT);
            byte[] laneSalt = in.readBytes(SALT_BYTES);
            return in.remaining() != 0 ? null : new Cookie13(nonceEcho, cookie, ybM, ybA, laneSalt);
        } catch (IOException truncated) {
            return null; // the null verdict IS the count hook (class javadoc)
        }
    }

    /**
     * {@code ADb = u8 cookieLen ‖ cookie ‖ YbM ‖ YbA ‖ laneSalt} = COOKIE13 payload[10..) byte for byte
     * (wire bytes verbatim, the lane salt included). The door recomputes it from the F2's cookie + that
     * cookie's bucket shares + its salt. {@code IllegalArgumentException} on a length breach (the caller's values).
     */
    public static byte[] adb(byte[] cookie, byte[] ybM, byte[] ybA, byte[] laneSalt) {
        requireCookie(cookie);
        requireLen(ybM, POINT, "ybM");
        requireLen(ybA, POINT, "ybA");
        requireLen(laneSalt, SALT_BYTES, "laneSalt");
        byte[] out = new byte[1 + cookie.length + COOKIE_TAIL_BYTES];
        out[0] = (byte) cookie.length;
        int at = 1;
        System.arraycopy(cookie, 0, out, at, cookie.length);
        at += cookie.length;
        System.arraycopy(ybM, 0, out, at, POINT);
        at += POINT;
        System.arraycopy(ybA, 0, out, at, POINT);
        at += POINT;
        System.arraycopy(laneSalt, 0, out, at, SALT_BYTES);
        return out;
    }

    /**
     * {@code handle = HMAC-SHA256(key = laneSalt, cid bytes)[0..16)} — the lane identifier that
     * rides in the clear while the cid rides sealed; the door compares it itself. A fresh {@code Mac} per call.
     * {@code IllegalArgumentException} on a salt not 16 B or a cid that is not an A-string(64).
     */
    public static byte[] laneHandle(byte[] laneSalt, String cid) {
        requireLen(laneSalt, SALT_BYTES, "laneSalt");
        requireAString(cid, CID_MAX, "cid");
        byte[] cidBytes = new byte[cid.length()];
        putAStringBody(cidBytes, 0, cid);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(laneSalt, "HmacSHA256"));
            return Arrays.copyOf(mac.doFinal(cidBytes), HANDLE_BYTES);
        } catch (GeneralSecurityException brokenInstall) {
            throw new IllegalStateException("HmacSHA256");
        }
    }

    /**
     * The ONE text → bytes function for a secret (a match password, an admin secret, a seat token) —
     * BOTH ends call it, so the {@code prs} / {@code mix} bytes can never differ by a charset choice. {@code null} or
     * blank ({@code String.isBlank}, the door's blank-credential law) = a zero-length array = "no secret"; otherwise
     * every {@code char} as two big-endian bytes, untrimmed — one-to-one for ANY text (a hand-written descriptor token
     * may hold any single line) and no {@code java.nio.charset} (classloader B). The result IS the secret: never log it.
     *
     */
    public static byte[] secretBytes(String secret) {
        if (secret == null || secret.isBlank()) {
            return new byte[0];
        }
        byte[] out = new byte[2 * secret.length()];
        for (int i = 0; i < secret.length(); i++) {
            char c = secret.charAt(i);
            out[2 * i] = (byte) (c >>> 8);
            out[2 * i + 1] = (byte) c;
        }
        return out;
    }

    // --- the key schedule (the envelope core; VCHK reuses it with its own ADa) -----------------------

    /** The client's half of one envelope: its share, both wraps, the content key and both roots. */
    public static final class ClientWraps {
        private final byte[] ya, wrapM, wrapA, ck, rootM, rootA;

        private ClientWraps(byte[] ya, byte[] wrapM, byte[] wrapA, byte[] ck, byte[] rootM, byte[] rootA) {
            this.ya = ya;
            this.wrapM = wrapM;
            this.wrapA = wrapA;
            this.ck = ck;
            this.rootM = rootM;
            this.rootA = rootA;
        }

        public byte[] ya() {
            return ya;
        }

        public byte[] wrapM() {
            return wrapM;
        }

        public byte[] wrapA() {
            return wrapA;
        }

        public byte[] ck() {
            return ck;
        }

        public byte[] rootM() {
            return rootM;
        }

        public byte[] rootA() {
            return rootA;
        }

        /** Never prints a byte (it holds the content key and both roots). */
        @Override
        public String toString() {
            return "Handshake13.ClientWraps";
        }
    }

    /**
     * The client side: {@code G = generator(prs, CI)}, a fresh scalar {@code a}, {@code Ya = a·G},
     * {@code K_x = a·Yb_x}, {@code ISK_x = isk(EMPTY, K_x, Ya, ADa, Yb_x, ADb)}, {@code root_x = ISK_x ‖ mix} (the
     * client seals BOTH wraps with ITS mix), {@code wrap_x = seal(hkdf(root_x, null, "cc-hs13 wrap x"), N0, EMPTY, ck)}
     * over 32 fresh bytes {@code ck}. {@code prs} = the typed secret's bytes (password lane) or EMPTY (token lane);
     * {@code mix} = the token's bytes (token lane) or EMPTY. Returns {@code null} when {@code scalarMult} refuses
     * (a small-order / malformed door share — the client aborts the envelope).
     * {@code IllegalArgumentException} on a null {@code prs} / {@code mix} / {@code ada} / {@code adb} / {@code rng}.
     * CALLER LAW: {@code rng} is the platform's UNSEEDED {@code SecureRandom} — a seeded or replayed
     * generator repeats {@code a} and {@code ck}, which reuses the wrap keys and the body key at their fixed nonces.
     */
    public static ClientWraps sealWrapsOrNull(byte[] prs, byte[] mix, byte[] ada, byte[] adb, byte[] ybM, byte[] ybA,
            SecureRandom rng) {
        requireNonNull(prs, "prs");
        requireNonNull(mix, "mix");
        requireNonNull(ada, "ada");
        requireNonNull(adb, "adb");
        if (rng == null) {
            throw new IllegalArgumentException("rng");
        }
        return sealWrapsOnGenerator(HandshakeCrypto.generator(prs, CI), mix, ada, adb, ybM, ybA, rng);
    }

    /**
     * {@link #sealWrapsOrNull} past its argument checks, on {@code g = generator(prs, CI)} already mapped (the map
     * draws nothing from {@code rng}, so mapping it before {@code a} leaves every draw where it was).
     *
     */
    private static ClientWraps sealWrapsOnGenerator(byte[] g, byte[] mix, byte[] ada, byte[] adb, byte[] ybM, byte[] ybA,
            SecureRandom rng) {
        byte[] a = HandshakeCrypto.newScalar(rng);
        byte[] ya = HandshakeCrypto.scalarMult(a, g);
        byte[] kM = HandshakeCrypto.scalarMult(a, ybM);
        byte[] kA = HandshakeCrypto.scalarMult(a, ybA);
        if (ya == null || kM == null || kA == null) {
            return null;
        }
        byte[] rootM = HandshakeCrypto.concat(HandshakeCrypto.isk(EMPTY, kM, ya, ada, ybM, adb), mix);
        byte[] rootA = HandshakeCrypto.concat(HandshakeCrypto.isk(EMPTY, kA, ya, ada, ybA, adb), mix);
        byte[] ck = new byte[KEY];
        rng.nextBytes(ck);
        byte[] wrapM = HandshakeCrypto.seal(HandshakeCrypto.hkdf(rootM, null, LABEL_WRAP_M, KEY), new byte[NONCE], EMPTY, ck);
        byte[] wrapA = HandshakeCrypto.seal(HandshakeCrypto.hkdf(rootA, null, LABEL_WRAP_A, KEY), new byte[NONCE], EMPTY, ck);
        return new ClientWraps(ya, wrapM, wrapA, ck, rootM, rootA);
    }

    /** The door's verdict on one envelope's wraps: which kind opened, the token index, the content key, the root. */
    public static final class DoorWraps {
        private final int kind, mixIndex;
        private final byte[] ck, root;

        private DoorWraps(int kind, int mixIndex, byte[] ck, byte[] root) {
            this.kind = kind;
            this.mixIndex = mixIndex;
            this.ck = ck;
            this.root = root;
        }

        /** {@link #KIND_MATCH} or {@link #KIND_ADMIN}. */
        public int kind() {
            return kind;
        }

        /** The index into the door's {@code mixesM} whose entry opened {@code wrapM}; -1 for {@link #KIND_ADMIN}. */
        public int mixIndex() {
            return mixIndex;
        }

        public byte[] ck() {
            return ck;
        }

        public byte[] root() {
            return root;
        }

        /** Never prints a byte (it holds the content key and the root). */
        @Override
        public String toString() {
            return kind == KIND_MATCH ? "Handshake13.DoorWraps[m]" : "Handshake13.DoorWraps[a]";
        }
    }

    /**
     * The door side, CONSTANT WORK: both {@code scalarMult}s, both {@code isk}s, EVERY
     * entry of {@code mixesM} against {@code wrapM}, then {@code wrapA} under EMPTY only — no early exit on a success.
     * {@code m} wins when both open; the FIRST {@code mixesM} entry that opens {@code wrapM} is the index. Returns
     * {@code null} when no wrap opens, when {@code scalarMult} refuses {@code ya} (a small-order share — the door
     * counts a FAIL), or when a network array ({@code ya}, {@code wrapM}, {@code wrapA}, {@code ada}) is null
     * or the wrong length. The door's own values are checked: a null / empty {@code mixesM} (or a null entry), a
     * scalar or share not 32 B, a null {@code adb} = {@code IllegalArgumentException}.
     */
    public static DoorWraps openWrapsOrNull(byte[] ya, byte[] wrapM, byte[] wrapA, byte[] ada, byte[] adb, byte[] bM,
            byte[] bA, byte[] ybM, byte[] ybA, byte[][] mixesM) {
        requireDoorArgs(bM, bA, ybM, ybA, mixesM);
        requireNonNull(adb, "adb");
        if (ya == null || ya.length != POINT || wrapM == null || wrapM.length != WRAP_BYTES || wrapA == null
                || wrapA.length != WRAP_BYTES || ada == null) {
            return null;
        }
        byte[] kM = HandshakeCrypto.scalarMult(bM, ya);
        byte[] kA = HandshakeCrypto.scalarMult(bA, ya);
        if (kM == null || kA == null) {
            return null;
        }
        byte[] iskM = HandshakeCrypto.isk(EMPTY, kM, ya, ada, ybM, adb);
        byte[] iskA = HandshakeCrypto.isk(EMPTY, kA, ya, ada, ybA, adb);
        int matchIndex = -1;
        byte[] matchCk = null;
        byte[] matchRoot = null;
        for (int i = 0; i < mixesM.length; i++) {
            byte[] root = HandshakeCrypto.concat(iskM, mixesM[i]);
            byte[] ck = HandshakeCrypto.open(HandshakeCrypto.hkdf(root, null, LABEL_WRAP_M, KEY), new byte[NONCE], EMPTY, wrapM);
            if (ck != null && matchIndex < 0) {
                matchIndex = i;
                matchCk = ck;
                matchRoot = root;
            }
        }
        byte[] adminCk = HandshakeCrypto.open(HandshakeCrypto.hkdf(iskA, null, LABEL_WRAP_A, KEY), new byte[NONCE], EMPTY, wrapA);
        if (matchIndex >= 0) {
            return new DoorWraps(KIND_MATCH, matchIndex, matchCk, matchRoot);
        }
        return adminCk == null ? null : new DoorWraps(KIND_ADMIN, -1, adminCk, iskA);
    }

    /**
     * {@code bodyKey = hkdf(ck, null, "cc-hs13 body c2s", 32)} (derived, never {@code ck} itself),
     * sealed at nonce {@code N(seq) = 4 zero bytes ‖ u64 seq}. {@code IllegalArgumentException} on a {@code ck} not
     * 32 B, a negative {@code seq}, a null {@code aad} / {@code plain}.
     * CALLER LAW: one {@code (ck, seq)} pair seals ONE body, ever — the nonce is fixed by {@code seq}
     * ({@link ClientWraps#ck()} is public for VCHK, which draws a distinct {@code seq} per datagram).
     */
    public static byte[] sealBody(byte[] ck, long seq, byte[] aad, byte[] plain) {
        requireLen(ck, KEY, "ck");
        if (seq < 0L) {
            throw new IllegalArgumentException("seq");
        }
        return HandshakeCrypto.seal(HandshakeCrypto.hkdf(ck, null, LABEL_BODY_C2S, KEY), nonceFor(seq), aad, plain);
    }

    /** The inverse of {@link #sealBody}: {@code null} on a bad tag, a {@code ck} not 32 B, a negative {@code seq} or a null array. */
    public static byte[] openBodyOrNull(byte[] ck, long seq, byte[] aad, byte[] sealed) {
        if (ck == null || ck.length != KEY || seq < 0L) {
            return null;
        }
        return HandshakeCrypto.open(HandshakeCrypto.hkdf(ck, null, LABEL_BODY_C2S, KEY), nonceFor(seq), aad, sealed);
    }

    // --- F2 HELLO13-SEAL --------------------------------------------------------------------------

    /** The client's sealed F2: the PAYLOAD (the caller frames it on {@code WireChannel.HELLO}) and both roots, kept to open the WELCOME13. */
    public static final class ClientSeal {
        private final byte[] payload, rootM, rootA;

        private ClientSeal(byte[] payload, byte[] rootM, byte[] rootA) {
            this.payload = payload;
            this.rootM = rootM;
            this.rootA = rootA;
        }

        public byte[] payload() {
            return payload;
        }

        public byte[] rootM() {
            return rootM;
        }

        public byte[] rootA() {
            return rootA;
        }

        /** Never prints a byte (it holds both roots). */
        @Override
        public String toString() {
            return "Handshake13.ClientSeal";
        }
    }

    /**
     * The client's F2 = {@code u8 VERSION ‖ u64 nonce ‖ u8 flags ‖ u8 cookieLen ‖ cookie ‖ handle[16] ‖
     * A-string(128) print ‖ Ya ‖ wrapM ‖ wrapA ‖ sealedBody}, 200..487 B. {@code H} = the bytes before {@code Ya};
     * {@code ADa = 0x48 ‖ H}; ADb = {@link #adb} of the cookie's own fields; the body {@code A-string(64) cid ‖ u64
     * desiredShipId ‖ A-string(64) variant ‖ u8 team ‖ keymap[16]} is sealed under {@link #sealBody} at {@code seq} 0
     * with every payload byte before it as AAD. This entry seals {@code team} 0 (none: the door picks) and no keymap
     * (16 zeros); the team pick is
     * {@link #sealHelloOrNull(long, int, Cookie13, String, String, long, String, int, byte[], byte[], SecureRandom)}. {@link #FLAG_NO_SECRET} is set HERE iff {@code prs} and {@code mix} are both empty (the
     * envelope is still complete, sealed on {@code G("")}). Returns {@code null} when
     * {@link #sealWrapsOrNull} does (a door share the JDK refuses = abort the envelope).
     * {@code IllegalArgumentException} (naming the field): {@code flags} outside {@link #CALLER_FLAGS} (the
     * {@code NO_SECRET} bit included), a null / malformed {@code cookie}, a {@code print} / {@code cid} /
     * {@code variantId} that is not an A-string of its cap, a negative {@code desiredShipId}, a null
     * {@code prs} / {@code mix} / {@code rng}. Maps {@code prs} on EVERY call; the join flight seals through
     * {@link #sealHelloOnGeneratorOrNull} instead (one map per flight).
     */
    public static ClientSeal sealHelloOrNull(long nonce, int flags, Cookie13 cookie, String print, String cid,
            long desiredShipId, String variantId, byte[] prs, byte[] mix, SecureRandom rng) {
        return sealHelloOrNull(nonce, flags, cookie, print, cid, desiredShipId, variantId, 0, prs, mix, rng);
    }

    /**
     * The F2 sealer with the joiner's {@code team} byte: 0 = none (the door picks), 1 = side 0, 2 = side 1 —
     * {@code IllegalArgumentException} "team" outside 0..{@value #TEAM_MAX}, after every other field check. Same
     * returns and other {@code IllegalArgumentException}s as the team-0 entry above.
     *
     */
    public static ClientSeal sealHelloOrNull(long nonce, int flags, Cookie13 cookie, String print, String cid,
            long desiredShipId, String variantId, int team, byte[] prs, byte[] mix, SecureRandom rng) {
        return sealHelloOrNull(nonce, flags, cookie, print, cid, desiredShipId, variantId, team, null, prs, mix, rng);
    }

    /**
     * The F2 sealer with the pilot's 16-byte control keymap closing the sealed body after the team byte:
     * {@code keymapOrNull} = one byte per {@link PilotIntentFrame#CONTROL_KEY_NAMES} entry (0 unmapped, 1..0xDF an
     * LWJGL key code, 0xF0..0xF7 a mouse button), null = 16 zeros (no map: the serve's own table answers);
     * {@code IllegalArgumentException} "keymap" on a non-null array that is not 16 lawful bytes, after the team check.
     * Same returns and other {@code IllegalArgumentException}s as the team entry above. Main-written
     */
    public static ClientSeal sealHelloOrNull(long nonce, int flags, Cookie13 cookie, String print, String cid,
            long desiredShipId, String variantId, int team, byte[] keymapOrNull, byte[] prs, byte[] mix,
            SecureRandom rng) {
        requireHelloArgs(flags, cookie, print, cid, variantId, desiredShipId, team, keymapOrNull);
        requireNonNull(prs, "prs");
        requireNonNull(mix, "mix");
        if (rng == null) {
            throw new IllegalArgumentException("rng");
        }
        return sealHelloChecked(nonce, flags, cookie, print, cid, desiredShipId, variantId, team,
                keymapOrNull == null ? keymapNone() : keymapOrNull.clone(), HandshakeCrypto.generator(prs, CI),
                prs.length == 0, mix, rng);
    }

    /**
     * {@link #sealHelloOrNull} on a generator the caller mapped ONCE ({@code generator = HandshakeCrypto.generator(prs,
     * ci())}) in place of {@code prs}: for the same inputs and draws, the same bytes (row KF1). {@code prsEmpty} = that
     * {@code prs} was zero-length ({@link #FLAG_NO_SECRET} = {@code prsEmpty} and an empty {@code mix}, as there). WHY:
     * the map is not constant-time and any door can make a client seal and
     * time the F2, so {@link Handshake13Flight} maps once, before its first datagram, and seals every F2 here. Same
     * returns and {@code IllegalArgumentException}s as {@link #sealHelloOrNull}, a null or non-32 B {@code generator}
     * in place of a null {@code prs}.
     *
     */
    public static ClientSeal sealHelloOnGeneratorOrNull(long nonce, int flags, Cookie13 cookie, String print, String cid,
            long desiredShipId, String variantId, byte[] generator, boolean prsEmpty, byte[] mix, SecureRandom rng) {
        return sealHelloOnGeneratorOrNull(nonce, flags, cookie, print, cid, desiredShipId, variantId, 0, generator,
                prsEmpty, mix, rng);
    }

    /**
     * {@link #sealHelloOnGeneratorOrNull} with the joiner's {@code team} byte (0..{@value #TEAM_MAX}, else
     * {@code IllegalArgumentException} "team") — the join flight's one sealer.
     *
     */
    public static ClientSeal sealHelloOnGeneratorOrNull(long nonce, int flags, Cookie13 cookie, String print, String cid,
            long desiredShipId, String variantId, int team, byte[] generator, boolean prsEmpty, byte[] mix,
            SecureRandom rng) {
        return sealHelloOnGeneratorOrNull(nonce, flags, cookie, print, cid, desiredShipId, variantId, team, null,
                generator, prsEmpty, mix, rng);
    }

    /**
     * {@link #sealHelloOnGeneratorOrNull} with the pilot's keymap after the team byte (the keymap law of the
     * {@link #sealHelloOrNull} keymap form: null = 16 zeros, "keymap" on a non-null array that is not 16 lawful bytes)
     * — the join flight's one sealer. Main-written
     */
    public static ClientSeal sealHelloOnGeneratorOrNull(long nonce, int flags, Cookie13 cookie, String print, String cid,
            long desiredShipId, String variantId, int team, byte[] keymapOrNull, byte[] generator, boolean prsEmpty,
            byte[] mix, SecureRandom rng) {
        requireHelloArgs(flags, cookie, print, cid, variantId, desiredShipId, team, keymapOrNull);
        requireLen(generator, HandshakeCrypto.POINT_BYTES, "generator");
        requireNonNull(mix, "mix");
        if (rng == null) {
            throw new IllegalArgumentException("rng");
        }
        return sealHelloChecked(nonce, flags, cookie, print, cid, desiredShipId, variantId, team,
                keymapOrNull == null ? keymapNone() : keymapOrNull.clone(), generator, prsEmpty, mix, rng);
    }

    /** 16 zero bytes = no map (the serve's own table answers). */
    public static byte[] keymapNone() {
        return new byte[KEYMAP_BYTES];
    }

    /** 16 bytes, each in {0, 1..0xDF, 0xF0..0xF7}; false for null or any other length. */
    public static boolean keymapLawful(byte[] km) {
        if (km == null || km.length != KEYMAP_BYTES) {
            return false;
        }
        for (byte b : km) {
            if (!PilotControlNames.lawful(b & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** Bytes 0..7 packed little-end (byte i at bits {@code (i << 3)..+7}); 0 for null. */
    public static long keymapLo(byte[] km) {
        return km == null ? 0L : pack(km, 0);
    }

    /** Bytes 8..15 packed little-end; 0 for null. */
    public static long keymapHi(byte[] km) {
        return km == null ? 0L : pack(km, 8);
    }

    private static long pack(byte[] km, int from) {
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v |= (km[from + i] & 0xFFL) << (i << 3);
        }
        return v;
    }

    /** The seal entries' shared checks, in their historic order (flags, cookie, print, cid, variant, ship), then team,
     *  then the keymap (null = none; a non-null array must be 16 lawful bytes). */
    private static void requireHelloArgs(int flags, Cookie13 cookie, String print, String cid, String variantId,
            long desiredShipId, int team, byte[] keymapOrNull) {
        if ((flags & ~CALLER_FLAGS) != 0) {
            throw new IllegalArgumentException("flags");
        }
        if (cookie == null) {
            throw new IllegalArgumentException("cookie");
        }
        requireAString(print, PRINT_MAX, "print");
        requireAString(cid, CID_MAX, "cid");
        requireAString(variantId, VARIANT_MAX, "variantId");
        if (desiredShipId < 0L) {
            throw new IllegalArgumentException("desiredShipId");
        }
        if (team < 0 || team > TEAM_MAX) {
            throw new IllegalArgumentException("team");
        }
        if (keymapOrNull != null && !keymapLawful(keymapOrNull)) {
            throw new IllegalArgumentException("keymap");
        }
    }

    /**
     * The one F2 body both seal entries share, past their checks: {@code g} = the mapped generator, {@code prsEmpty}
     * = the mapped {@code prs} was zero-length; {@code team} then the 16-byte {@code keymap} (a private copy, lawful)
     * close the sealed body.
     *
     */
    private static ClientSeal sealHelloChecked(long nonce, int flags, Cookie13 cookie, String print, String cid,
            long desiredShipId, String variantId, int team, byte[] keymap, byte[] g, boolean prsEmpty, byte[] mix,
            SecureRandom rng) {
        byte[] adb = adb(cookie.cookie(), cookie.ybM(), cookie.ybA(), cookie.laneSalt());
        byte[] handle = laneHandle(cookie.laneSalt(), cid);
        byte[] cookieBytes = cookie.cookie();
        int wireFlags = flags | (prsEmpty && mix.length == 0 ? FLAG_NO_SECRET : 0);
        byte[] h = new byte[1 + 8 + 1 + 1 + cookieBytes.length + HANDLE_BYTES + 1 + print.length()];
        h[0] = (byte) VERSION;
        DatagramFrameCodec.putU64(h, 1, nonce);
        h[9] = (byte) wireFlags;
        h[10] = (byte) cookieBytes.length;
        System.arraycopy(cookieBytes, 0, h, 11, cookieBytes.length);
        System.arraycopy(handle, 0, h, 11 + cookieBytes.length, HANDLE_BYTES);
        putAString(h, 11 + cookieBytes.length + HANDLE_BYTES, print);
        byte[] ada = HandshakeCrypto.concat(new byte[] {AD_PURPOSE_HELLO}, h);
        ClientWraps wraps = sealWrapsOnGenerator(g, mix, ada, adb, cookie.ybM(), cookie.ybA(), rng);
        if (wraps == null) {
            return null;
        }
        byte[] body = new byte[1 + cid.length() + 8 + 1 + variantId.length() + 1 + KEYMAP_BYTES];
        int at = putAString(body, 0, cid);
        DatagramFrameCodec.putU64(body, at, desiredShipId);
        int t = putAString(body, at + 8, variantId);
        body[t] = (byte) team;
        System.arraycopy(keymap, 0, body, t + 1, KEYMAP_BYTES);
        byte[] prefix = HandshakeCrypto.concat(h, wraps.ya, wraps.wrapM, wraps.wrapA);
        byte[] sealedBody = sealBody(wraps.ck, 0L, prefix, body);
        return new ClientSeal(HandshakeCrypto.concat(prefix, sealedBody), wraps.rootM, wraps.rootA);
    }

    /**
     * One parsed F2, every clear field plus the two derived byte runs the door feeds the crypto: {@code ada} =
     * {@code 0x48 ‖ H} and {@code bodyAad} = every payload byte before {@code sealedBody}.
     */
    public record Seal13(long nonce, int flags, byte[] cookie, byte[] handle, String print, byte[] ya, byte[] wrapM,
            byte[] wrapA, byte[] sealedBody, byte[] ada, byte[] bodyAad) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Seal13 other && nonce == other.nonce && flags == other.flags
                    && Arrays.equals(cookie, other.cookie) && Arrays.equals(handle, other.handle)
                    && (print == null ? other.print == null : print.equals(other.print))
                    && Arrays.equals(ya, other.ya) && Arrays.equals(wrapM, other.wrapM)
                    && Arrays.equals(wrapA, other.wrapA) && Arrays.equals(sealedBody, other.sealedBody)
                    && Arrays.equals(ada, other.ada) && Arrays.equals(bodyAad, other.bodyAad);
        }

        @Override
        public int hashCode() {
            int h = 31 * Long.hashCode(nonce) + flags;
            h = 31 * h + Arrays.hashCode(cookie);
            h = 31 * h + Arrays.hashCode(handle);
            h = 31 * h + (print == null ? 0 : print.hashCode());
            h = 31 * h + Arrays.hashCode(ya);
            h = 31 * h + Arrays.hashCode(wrapM);
            h = 31 * h + Arrays.hashCode(wrapA);
            h = 31 * h + Arrays.hashCode(sealedBody);
            h = 31 * h + Arrays.hashCode(ada);
            return 31 * h + Arrays.hashCode(bodyAad);
        }

        /** Never prints the print (player-side text) or any byte. */
        @Override
        public String toString() {
            return "Handshake13.Seal13";
        }
    }

    /**
     * The door's FIRST step: a fixed-layout parse of an F2 payload, NO crypto and no allocation proportional
     * to anything but the payload — so the door can run its own checks (cookie, ledger, print) before it pays for an
     * open. {@code null} unless: length 184..{@value #SEAL_PAYLOAD_MAX_BYTES}, {@code [0] == VERSION}, flags within
     * {@link #FLAGS_DEFINED} (all five bits accepted), cookieLen 1..32 (so an F1, whose {@code [10]} is 0, never
     * parses here), the print an A-string(128), and a sealed body of 27..155 B (an 11..139 B body + its tag).
     */
    public static Seal13 parseSealOrNull(byte[] payload) {
        if (payload == null || payload.length < SEAL_PAYLOAD_MIN_BYTES || payload.length > SEAL_PAYLOAD_MAX_BYTES) {
            return null;
        }
        try {
            DatagramFrameCodec.PayloadReader in = new DatagramFrameCodec.PayloadReader(payload);
            if (in.readU8() != VERSION) {
                return null;
            }
            long nonce = in.readU64();
            int flags = in.readU8();
            int cookieLen = in.readU8();
            if ((flags & ~FLAGS_DEFINED) != 0 || cookieLen < 1 || cookieLen > COOKIE_MAX_BYTES) {
                return null;
            }
            byte[] cookie = in.readBytes(cookieLen);
            byte[] handle = in.readBytes(HANDLE_BYTES);
            String print = readAStringOrNull(in, PRINT_MAX);
            if (print == null) {
                return null;
            }
            int hLen = payload.length - in.remaining();
            byte[] ya = in.readBytes(POINT);
            byte[] wrapM = in.readBytes(WRAP_BYTES);
            byte[] wrapA = in.readBytes(WRAP_BYTES);
            int sealedLen = in.remaining();
            if (sealedLen < BODY_MIN_BYTES + TAG || sealedLen > BODY_MAX_BYTES + TAG) {
                return null;
            }
            byte[] sealedBody = in.readBytes(sealedLen);
            byte[] ada = new byte[1 + hLen];
            ada[0] = AD_PURPOSE_HELLO;
            System.arraycopy(payload, 0, ada, 1, hLen);
            byte[] bodyAad = Arrays.copyOf(payload, payload.length - sealedLen);
            return new Seal13(nonce, flags, cookie, handle, print, ya, wrapM, wrapA, sealedBody, ada, bodyAad);
        } catch (IOException truncated) {
            return null; // the null verdict IS the count hook (class javadoc)
        }
    }

    /**
     * The door's verdict on one F2: the kind and token index that opened, the root (the WELCOME13 key's input), and
     * — only when {@link #bodyOk()} — the sealed body's five fields (the team byte and the keymap included).
     */
    public static final class Opened {
        private final int kind, mixIndex;
        private final byte[] root;
        private final boolean bodyOk;
        private final String cid, variantId;
        private final long desiredShipId;
        private final int team;
        private final byte[] keymap;

        private Opened(int kind, int mixIndex, byte[] root, boolean bodyOk, String cid, long desiredShipId,
                String variantId, int team, byte[] keymap) {
            this.kind = kind;
            this.mixIndex = mixIndex;
            this.root = root;
            this.bodyOk = bodyOk;
            this.cid = cid;
            this.desiredShipId = desiredShipId;
            this.variantId = variantId;
            this.team = team;
            this.keymap = keymap;
        }

        public int kind() {
            return kind;
        }

        public int mixIndex() {
            return mixIndex;
        }

        public byte[] root() {
            return root;
        }

        /** False = a wrap opened (the secret was right) but the body's tag or its decode failed. */
        public boolean bodyOk() {
            return bodyOk;
        }

        /** The sealed cid; {@code null} when {@code !bodyOk}. */
        public String cid() {
            return cid;
        }

        /** The sealed desiredShipId ({@code >= 0}); -1 when {@code !bodyOk} (outside the wire domain on purpose). */
        public long desiredShipId() {
            return desiredShipId;
        }

        /** The sealed variant id; {@code null} when {@code !bodyOk}. */
        public String variantId() {
            return variantId;
        }

        /** The sealed team byte (0..{@value #TEAM_MAX}); -1 when {@code !bodyOk} (outside the wire domain on purpose).
         * */
        public int team() {
            return team;
        }

        /** The sealed 16-byte pilot keymap (0 unmapped, 1..0xDF a key code, 0xF0..0xF7 a mouse button; all zeros
         *  = no map), a fresh copy; {@code null} when {@code !bodyOk}. Main-written */
        public byte[] keymap() {
            return keymap == null ? null : keymap.clone();
        }

        /** Never prints the root or the body's player-side text. */
        @Override
        public String toString() {
            return "Handshake13.Opened";
        }
    }

    /**
     * The door's SECOND step, after its own checks: {@code ADb} = {@link #adb}{@code (seal.cookie, ybM,
     * ybA, laneSalt)} (the lane salt enters ONLY here; the door compares {@code seal.handle} itself), then
     * {@link #openWrapsOrNull} (constant work), then the body. {@code null} when no wrap opens (a {@code null} from
     * {@code scalarMult} included — a small-order {@code Ya}) or when {@code seal} is null / not parser-shaped; a
     * non-null {@link Opened} with {@code bodyOk == false} when a wrap opened but the body's tag or decode failed,
     * so the door can tell "wrong secret" from "right secret, bad body". The body decode refuses a
     * non-A-string cid / variant, a negative desiredShipId, a missing or out-of-range (3..255) team byte, a short keymap,
     * an out-of-domain keymap byte and trailing bytes; a refused team or keymap byte is never surfaced (the caller
     * sees only {@code bodyOk == false}). The door's own arguments throw
     * {@code IllegalArgumentException} as in {@link #openWrapsOrNull} (+ a salt not 16 B).
     */
    public static Opened openHelloOrNull(Seal13 seal, byte[] bM, byte[] bA, byte[] ybM, byte[] ybA, byte[] laneSalt,
            byte[][] mixesM) {
        requireDoorArgs(bM, bA, ybM, ybA, mixesM);
        requireLen(laneSalt, SALT_BYTES, "laneSalt");
        if (seal == null || seal.cookie() == null || seal.cookie().length < 1 || seal.cookie().length > COOKIE_MAX_BYTES
                || seal.bodyAad() == null || seal.sealedBody() == null) {
            return null;
        }
        byte[] adb = adb(seal.cookie(), ybM, ybA, laneSalt);
        DoorWraps wraps = openWrapsOrNull(seal.ya(), seal.wrapM(), seal.wrapA(), seal.ada(), adb, bM, bA, ybM, ybA, mixesM);
        if (wraps == null) {
            return null;
        }
        byte[] plain = openBodyOrNull(wraps.ck, 0L, seal.bodyAad(), seal.sealedBody());
        if (plain != null) {
            try {
                DatagramFrameCodec.PayloadReader in = new DatagramFrameCodec.PayloadReader(plain);
                String cid = readAStringOrNull(in, CID_MAX);
                long desiredShipId = in.readU64();
                String variantId = readAStringOrNull(in, VARIANT_MAX);
                int team = in.readU8();
                byte[] km = in.readBytes(KEYMAP_BYTES);
                if (cid != null && variantId != null && desiredShipId >= 0L && team <= TEAM_MAX && keymapLawful(km)
                        && in.remaining() == 0) {
                    return new Opened(wraps.kind, wraps.mixIndex, wraps.root, true, cid, desiredShipId, variantId, team,
                            km);
                }
            } catch (IOException truncated) {
                // falls through to the bodyOk == false verdict below, which the caller counts (class javadoc)
            }
        }
        return new Opened(wraps.kind, wraps.mixIndex, wraps.root, false, null, -1L, null, -1, null);
    }

    // --- WELCOME13 ADMITTED -----------------------------------------------------------------------

    /**
     * The status peek on a WELCOME payload: 0 (ADMITTED), 1 (REJECTED, today's plaintext grammar at byte
     * VERSION), 2 (COOKIE13) when {@code payload.length >= 2}, {@code payload[0] == VERSION} and the status byte is 0..2;
     * else -1. It routes only — it admits nothing (a plaintext ADMITTED does not exist at this version).
     */
    public static int statusOrNegative(byte[] payload) {
        if (payload == null || payload.length < 2 || (payload[0] & 0xFF) != VERSION) {
            return -1;
        }
        int status = payload[1] & 0xFF;
        return status <= STATUS_MAX ? status : -1;
    }

    /**
     * {@code u8 VERSION ‖ u8 0 ‖ u64 nonceEcho ‖ doorSalt[16] ‖ sealed[81]} (107 B payload), FRAMED on
     * {@code WireChannel.WELCOME}. {@code sealed = seal(hkdf(root, doorSalt, "cc-hs13 welcome"), N0, aad =
     * payload[0..26), admitted)} with {@code admitted} (65 B) = the v12 ADMITTED fields in order minus the reason
     * ({@code serveSchemaMax} written here, as {@code HandshakeCodec}'s encoder does) + {@code YbAuth}. {@code doorSalt}
     * must be 16 FRESH random bytes per seal (the fixed nonce is safe only because of it). A negative
     * {@code boundShipId} travels as 0 (the v2 "none" sentinel, as {@code HandshakeCodec.encodeAdmitted}).
     * {@code IllegalArgumentException} (the door is the caller) on: a salt not 16 B, a root shorter than an ISK,
     * {@code startingRoundId} outside 0..255, {@code tickRate} outside 1..65535, {@code matchFlags} outside
     * {@code WELCOME_FLAGS_DEFINED}, {@code serverLife == 0}, a custom tail outside {@code customTailValid}, a
     * {@code ybAuth} not 32 B.
     */
    public static byte[] encodeAdmitted(long nonceEcho, byte[] doorSalt, byte[] root, long sessionId, int startingRoundId,
            long boundShipId, int tickRate, int matchFlags, long serverLife, int customTier, int customPerSeat,
            int customSModCap, int customFlags, byte[] ybAuth) {
        requireLen(doorSalt, SALT_BYTES, "doorSalt");
        requireRoot(root);
        if (startingRoundId < 0 || startingRoundId > 0xFF) {
            throw new IllegalArgumentException("startingRoundId");
        }
        if (tickRate < 1 || tickRate > 0xFFFF) {
            throw new IllegalArgumentException("tickRate");
        }
        if ((matchFlags & ~HandshakeCodec.WELCOME_FLAGS_DEFINED) != 0) {
            throw new IllegalArgumentException("matchFlags");
        }
        if (serverLife == 0L) {
            throw new IllegalArgumentException("serverLife");
        }
        if (!HandshakeCodec.WelcomePayload.customTailValid(customTier, customPerSeat, customSModCap, customFlags)) {
            throw new IllegalArgumentException("customTail");
        }
        requireLen(ybAuth, POINT, "ybAuth");
        byte[] admitted = new byte[ADMITTED_PLAIN_BYTES];
        DatagramFrameCodec.putU64(admitted, 0, sessionId);
        admitted[8] = (byte) startingRoundId;
        DatagramFrameCodec.putU64(admitted, 9, boundShipId < 0L ? 0L : boundShipId);
        DatagramFrameCodec.putU16(admitted, 17, tickRate);
        admitted[19] = (byte) AuthoritativeWorldSnapshotCodec.SCHEMA_VERSION_MAX;
        admitted[20] = (byte) matchFlags;
        DatagramFrameCodec.putU64(admitted, 21, serverLife);
        admitted[29] = (byte) customTier;
        admitted[30] = (byte) customPerSeat;
        admitted[31] = (byte) customSModCap;
        admitted[32] = (byte) customFlags;
        System.arraycopy(ybAuth, 0, admitted, 33, POINT);
        byte[] payload = new byte[WELCOME_PAYLOAD_BYTES];
        payload[0] = (byte) VERSION;
        payload[1] = (byte) STATUS_ADMITTED;
        DatagramFrameCodec.putU64(payload, 2, nonceEcho);
        System.arraycopy(doorSalt, 0, payload, 10, SALT_BYTES);
        byte[] sealed = HandshakeCrypto.seal(HandshakeCrypto.hkdf(root, doorSalt, LABEL_WELCOME, KEY), new byte[NONCE],
                Arrays.copyOf(payload, WELCOME_AAD_BYTES), admitted);
        System.arraycopy(sealed, 0, payload, WELCOME_AAD_BYTES, sealed.length);
        return frame(DatagramFrameCodec.WireChannel.WELCOME, payload);
    }

    /** One opened WELCOME13: the v12-shaped record the kit already consumes, the kind that opened, the door's auth share, the session root. */
    public record Admitted13(HandshakeCodec.WelcomePayload welcome, int kind, byte[] ybAuth, byte[] sessionRoot) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Admitted13 other && kind == other.kind
                    && (welcome == null ? other.welcome == null : welcome.equals(other.welcome))
                    && Arrays.equals(ybAuth, other.ybAuth) && Arrays.equals(sessionRoot, other.sessionRoot);
        }

        @Override
        public int hashCode() {
            int h = 31 * (welcome == null ? 0 : welcome.hashCode()) + kind;
            h = 31 * h + Arrays.hashCode(ybAuth);
            return 31 * h + Arrays.hashCode(sessionRoot);
        }

        /** Never prints the session root or any byte. */
        @Override
        public String toString() {
            return "Handshake13.Admitted13";
        }
    }

    /**
     * The client side: tries {@code sealed.rootM} then {@code sealed.rootA}; the one that opens IS the kind.
     * {@code null} on a null argument, a length other than 107, a version / status byte other than 13 / 0, a failed
     * tag under both roots (so a door without the secret cannot admit, and a v12 plaintext ADMITTED with its version
     * byte patched to 13 opens nowhere), or an admitted body outside the {@code HandshakeCodec} ADMITTED decode laws
     * ({@code boundShipId < 0}, {@code tickRate == 0}, {@code serveSchemaMax == 0}, an undefined {@code matchFlags}
     * bit, {@code serverLife == 0}, the custom tail). The nonce echo is authenticated (it is AAD); matching it against
     * the HELLO the kit sent stays the caller's, as today. {@code sessionRoot = hkdf(root, doorSalt, "cc-hs13 session")}.
     * The client's kind is EVIDENCE, never authority: whoever sealed this WELCOME13 chose the root,
     * and any holder of the secret the player typed can seal under either — against the genuine door the two ends'
     * kinds agree, and the kit grants nothing on it (every admin verb is re-checked on the door's own session flag).
     */
    public static Admitted13 openAdmittedOrNull(byte[] payload, ClientSeal sealed) {
        if (payload == null || sealed == null || payload.length != WELCOME_PAYLOAD_BYTES
                || (payload[0] & 0xFF) != VERSION || payload[1] != STATUS_ADMITTED) {
            return null;
        }
        byte[] doorSalt = Arrays.copyOfRange(payload, 10, WELCOME_AAD_BYTES);
        byte[] aad = Arrays.copyOf(payload, WELCOME_AAD_BYTES);
        byte[] box = Arrays.copyOfRange(payload, WELCOME_AAD_BYTES, WELCOME_PAYLOAD_BYTES);
        int kind = KIND_MATCH;
        byte[] root = sealed.rootM;
        byte[] plain = HandshakeCrypto.open(HandshakeCrypto.hkdf(root, doorSalt, LABEL_WELCOME, KEY), new byte[NONCE], aad, box);
        if (plain == null) {
            kind = KIND_ADMIN;
            root = sealed.rootA;
            plain = HandshakeCrypto.open(HandshakeCrypto.hkdf(root, doorSalt, LABEL_WELCOME, KEY), new byte[NONCE], aad, box);
        }
        if (plain == null || plain.length != ADMITTED_PLAIN_BYTES) {
            return null;
        }
        long sessionId = u64(plain, 0);
        int startingRoundId = plain[8] & 0xFF;
        long boundShipId = u64(plain, 9);
        int tickRate = ((plain[17] & 0xFF) << 8) | (plain[18] & 0xFF);
        int serveSchemaMax = plain[19] & 0xFF;
        int matchFlags = plain[20] & 0xFF;
        long serverLife = u64(plain, 21);
        int customTier = plain[29] & 0xFF;
        int customPerSeat = plain[30] & 0xFF;
        int customSModCap = plain[31];
        int customFlags = plain[32] & 0xFF;
        if (boundShipId < 0L || tickRate == 0 || serveSchemaMax == 0
                || (matchFlags & ~HandshakeCodec.WELCOME_FLAGS_DEFINED) != 0 || serverLife == 0L
                || !HandshakeCodec.WelcomePayload.customTailValid(customTier, customPerSeat, customSModCap, customFlags)) {
            return null;
        }
        HandshakeCodec.WelcomePayload welcome = new HandshakeCodec.WelcomePayload(STATUS_ADMITTED, u64(payload, 2),
                sessionId, startingRoundId, "", boundShipId, tickRate, serveSchemaMax, matchFlags, serverLife,
                customTier, customPerSeat, customSModCap, customFlags);
        return new Admitted13(welcome, kind, Arrays.copyOfRange(plain, 33, ADMITTED_PLAIN_BYTES),
                HandshakeCrypto.hkdf(root, doorSalt, LABEL_SESSION, KEY));
    }

    /**
     * The door's half of {@code sessionRoot = hkdf(root, doorSalt, "cc-hs13 session", 32)} (lane iv's input;
     * the client gets the same 32 B from {@link #openAdmittedOrNull}). {@code IllegalArgumentException} on a root
     * shorter than an ISK or a salt not 16 B.
     */
    public static byte[] sessionRoot(byte[] root, byte[] doorSalt) {
        requireRoot(root);
        requireLen(doorSalt, SALT_BYTES, "doorSalt");
        return HandshakeCrypto.hkdf(root, doorSalt, LABEL_SESSION, KEY);
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private static byte[] frame(DatagramFrameCodec.WireChannel channel, byte[] payload) {
        return DatagramFrameCodec.encode(channel, 0, 0L, 0L, 0L, 0, 0L, payload);
    }

    /** {@code N(seq) = 4 zero bytes ‖ u64 seq} ({@code N(0)} equals {@code N0}; the keys differ). */
    private static byte[] nonceFor(long seq) {
        byte[] nonce = new byte[NONCE];
        DatagramFrameCodec.putU64(nonce, 4, seq);
        return nonce;
    }

    private static long u64(byte[] b, int off) {
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }

    /** A-string encode precondition: non-null, {@code length <= max}, every char 0x21..0x7E. */
    private static void requireAString(String s, int max, String field) {
        if (s == null || s.length() > max) {
            throw new IllegalArgumentException(field);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new IllegalArgumentException(field);
            }
        }
    }

    /** Writes {@code u8 len ‖ chars} (already {@link #requireAString}-checked) at {@code off}; returns the offset past it. */
    private static int putAString(byte[] out, int off, String s) {
        out[off] = (byte) s.length();
        return putAStringBody(out, off + 1, s);
    }

    private static int putAStringBody(byte[] out, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            out[off++] = (byte) s.charAt(i);
        }
        return off;
    }

    /** A-string decode: {@code null} on a length over {@code max} or a byte outside 0x21..0x7E; the String is built from a {@code char[]}. */
    private static String readAStringOrNull(DatagramFrameCodec.PayloadReader in, int max) throws IOException {
        int len = in.readU8();
        if (len > max) {
            return null;
        }
        byte[] raw = in.readBytes(len);
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            int v = raw[i] & 0xFF;
            if (v < 0x21 || v > 0x7E) {
                return null;
            }
            chars[i] = (char) v;
        }
        return new String(chars);
    }

    private static void requireDoorArgs(byte[] bM, byte[] bA, byte[] ybM, byte[] ybA, byte[][] mixesM) {
        requireLen(bM, HandshakeCrypto.SCALAR_BYTES, "bM");
        requireLen(bA, HandshakeCrypto.SCALAR_BYTES, "bA");
        requireLen(ybM, POINT, "ybM");
        requireLen(ybA, POINT, "ybA");
        if (mixesM == null || mixesM.length == 0) {
            throw new IllegalArgumentException("mixesM");
        }
        for (byte[] mix : mixesM) {
            if (mix == null) {
                throw new IllegalArgumentException("mixesM");
            }
        }
    }

    /** {@code root_x = ISK_x ‖ mix}: at least the 64 B ISK. */
    private static void requireRoot(byte[] root) {
        if (root == null || root.length < ISK) {
            throw new IllegalArgumentException("root");
        }
    }

    private static void requireCookie(byte[] cookie) {
        if (cookie == null || cookie.length < 1 || cookie.length > COOKIE_MAX_BYTES) {
            throw new IllegalArgumentException("cookie");
        }
    }

    private static void requireLen(byte[] value, int len, String field) {
        if (value == null || value.length != len) {
            throw new IllegalArgumentException(field);
        }
    }

    private static void requireNonNull(byte[] value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field);
        }
    }

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        putAStringBody(out, 0, s);
        return out;
    }
}
