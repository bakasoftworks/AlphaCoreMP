package cc.starsector.server.runtime;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The admission door's STATELESS return-routability cookie (the DTLS
 * HelloVerifyRequest / QUIC Retry shape). WHY: the credential-failure budget
 * bounds wrong-answer REPLIES, never the GUESS rate — a guesser that ignores replies was bounded
 * by password entropy alone, and a per-source budget holding SUCCESSES was unsafe because a
 * pre-admission source address is spoofable (a spoofer could hold a victim's address).
 *
 * <p>Law: the door answers a HELLO that carries no VALID cookie with a tiny COOKIE reply
 * ({@code WELCOME_COOKIE}) minted for the datagram's OWN source {@code address:port} and the
 * current time bucket — and does NOTHING else: no fingerprint check, no credential compare, no
 * seat/ledger touch, no budget spend. Only a HELLO whose cookie verifies reaches a credential
 * compare; that source has proven it receives at its claimed address, so the per-verified-source
 * attempt budget ({@link DoorGuessLedger}) may hold every compare, pass or
 * fail, without a spoofable targeted DoS. The reply is at most the request's size (the caller
 * enforces {@code reply.length <= request.length} — a 1:1 reflector, never an amplifier).
 *
 * <p>Shape: {@value #BYTES} bytes = {@code u8 bucketLow} + the first {@value #MAC_BYTES} bytes of
 * {@code HMAC-SHA256(key, addressBytes || u16 port || u64 bucket)}; bucket = {@code now /
 * BUCKET_MILLIS}. {@link #verify} recomputes for the CURRENT and the PREVIOUS bucket (a client
 * has ≥ {@value #BUCKET_MILLIS} ms to answer; an expired cookie earns a fresh challenge, never a
 * reject) and compares constant-time ({@link MessageDigest#isEqual}). The key is per-serve
 * ({@link SecureRandom}, {@value #KEY_BYTES} bytes) — every serve invalidates every older
 * cookie; no key ever leaves the JVM and no cookie is ever logged. Stateless: nothing is
 * remembered per challenge, so a challenge flood costs one HMAC each and zero memory.
 *
 * <p>Threading: the door's single reader thread mints and verifies OUTSIDE its admission lock
 * (like the ladder classify — {@code PilotIntentDatagramServer.handleHello}); the class is
 * {@code synchronized} because {@link Mac} is not thread-safe (the lock is uncontended in
 * production; tests may share an instance).
 */
public final class DoorCookie {

    /** Cookie wire size: bucket low byte + truncated MAC. */
    public static final int BYTES = 17;
    /** MAC bytes kept on the wire (128-bit truncation; forging needs the key, not a collision). */
    public static final int MAC_BYTES = 16;
    /** HMAC key size. */
    public static final int KEY_BYTES = 32;
    /** Time bucket width: a cookie verifies for its own bucket and the next one (≥ 60 s window). */
    public static final long BUCKET_MILLIS = 60_000L;
    /** Upper bound the codec enforces on a HELLO's cookie tail (fail-closed above it). */
    public static final int MAX_WIRE_BYTES = 32;
    /** {@link #bucketOfVerified}'s "did not verify" — no {@link #bucketOf} value can equal it. */
    public static final long NO_BUCKET = Long.MIN_VALUE;

    private static final String ALGORITHM = "HmacSHA256";

    private final Mac mac;

    /** Per-serve key from {@link SecureRandom}. */
    public static DoorCookie fresh() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return new DoorCookie(key);
    }

    /** Fixed key — tests and the {@link #fresh()} factory. */
    public DoorCookie(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("cookie key must be " + KEY_BYTES + " bytes, was " + key.length);
        }
        try {
            Mac m = Mac.getInstance(ALGORITHM);
            m.init(new SecretKeySpec(key, ALGORITHM));
            this.mac = m;
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is a mandatory JCA algorithm on every Java SE runtime — its absence is a
            // broken JRE, a serve-fatal fact the door surfaces at construction, never per HELLO.
            throw new IllegalStateException("HmacSHA256 unavailable: " + e.getMessage(), e);
        }
    }

    /** Mint the cookie for {@code source} at {@code nowMillis} (current bucket). */
    public synchronized byte[] mint(InetSocketAddress source, long nowMillis) {
        Objects.requireNonNull(source, "source");
        long bucket = bucketOf(nowMillis);
        byte[] out = new byte[BYTES];
        out[0] = (byte) bucket;
        System.arraycopy(macFor(source, bucket), 0, out, 1, MAC_BYTES);
        return out;
    }

    /**
     * True iff {@code cookie} was minted by THIS key for THIS source in the current or the
     * previous bucket. Null / wrong-length / foreign-source / stale cookies are simply false —
     * the door answers false with a fresh challenge, so the shape is never a reject reason.
     */
    public synchronized boolean verify(InetSocketAddress source, byte[] cookie, long nowMillis) {
        return bucketOfVerified(source, cookie, nowMillis) != NO_BUCKET;
    }

    /**
     * {@link #verify}'s check, returning WHICH bucket the cookie verified in: {@code bucketOf(nowMillis)}
     * (minted this bucket) or {@code bucketOf(nowMillis) - 1} (minted the previous one), else
     * {@link #NO_BUCKET} — for every input {@link #verify} answers false on. WHY: the hs-13 door keys its
     * per-bucket CPace shares ({@link DoorShares13}) and its admitted-nonce memory by the bucket the
     * client's cookie was minted in ({@code verify} returned no bucket). The number
     * is SIGNED (the door clock is {@code System.nanoTime() / 1_000_000}, so it may be negative).
     *
     *
     */
    public synchronized long bucketOfVerified(InetSocketAddress source, byte[] cookie, long nowMillis) {
        if (source == null || cookie == null || cookie.length != BYTES) {
            return NO_BUCKET;
        }
        long current = bucketOf(nowMillis);
        // The low byte picks which of the two live buckets to check; a byte matching neither is
        // stale (or forged) and fails without an HMAC. Both branches run the same compare shape.
        long bucket;
        if (cookie[0] == (byte) current) {
            bucket = current;
        } else if (cookie[0] == (byte) (current - 1)) {
            bucket = current - 1;
        } else {
            return NO_BUCKET;
        }
        byte[] expected = new byte[BYTES];
        expected[0] = (byte) bucket;
        System.arraycopy(macFor(source, bucket), 0, expected, 1, MAC_BYTES);
        return MessageDigest.isEqual(expected, cookie) ? bucket : NO_BUCKET;
    }

    /**
     * The cookie time bucket of {@code nowMillis} = {@code floorDiv(nowMillis, BUCKET_MILLIS)}: SIGNED
     * (negative for a negative clock), {@code |bucket| <= Long.MAX_VALUE / BUCKET_MILLIS + 1}, so
     * {@code bucket +/- 1} never overflows and never equals {@link #NO_BUCKET}. Public because the hs-13
     * share table ({@link DoorShares13}) is keyed by it.
     */
    public static long bucketOf(long nowMillis) {
        return Math.floorDiv(nowMillis, BUCKET_MILLIS);
    }

    private byte[] macFor(InetSocketAddress source, long bucket) {
        InetAddress addr = source.getAddress();
        // An unresolved InetSocketAddress has no address bytes; the datagram socket always hands
        // the door a resolved one, so this is a test/misuse seam — bind the host string instead
        // rather than minting an address-free (transferable) cookie.
        byte[] addrBytes = addr != null
                ? addr.getAddress()
                : source.getHostString().getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[addrBytes.length + 2 + 8];
        System.arraycopy(addrBytes, 0, input, 0, addrBytes.length);
        int port = source.getPort();
        input[addrBytes.length] = (byte) (port >>> 8);
        input[addrBytes.length + 1] = (byte) port;
        DatagramFrameCodec.putU64(input, addrBytes.length + 2, bucket);
        mac.reset();
        byte[] full = mac.doFinal(input);
        byte[] truncated = new byte[MAC_BYTES];
        System.arraycopy(full, 0, truncated, 0, MAC_BYTES);
        return truncated;
    }
}
