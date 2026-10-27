package cc.starsector.server.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The ONE one-way digest of a client id, and its grammar. It is the custom-ship
 * author key on the match record, the form every server log row and reel row prints a player key in, and the key a
 * player ban is stored under in the bans file and asked by at the door, so a key a listing prints is the key the ban
 * and unban verbs take.
 *
 * <p>Shape: {@value #PREFIX} then 32 lowercase hex chars (length 35, ASCII) = the first 16 bytes (128 bits) of SHA-256
 * over the UTF-8 bytes of the domain tag plus the client id. The tag keeps it from equalling any other hash of the same
 * id; the {@code h1} prefix names the shape, so a future change bumps it rather than silently re-keying old records,
 * and it puts the digest off the hex key alphabet, so a token reads as a hex key or as a digest, never both.
 * UNKEYED and UNSALTED on purpose: a ban list, a recording and a log line name one player alike, and the harm guarded
 * is recovering the id, which a 128-bit random kit id makes infeasible. A hand-chosen low-entropy id is the limit: its
 * digest can be brute-forced back from a shared file.
 *
 * <p>Pure and thread-safe: no state, no call out. SHA-256 is a mandatory JRE algorithm, so its absence is a broken
 * runtime and throws, never a null key.
 */
public final class CidDigest {

    /** The digest's leading tag ({@link #isDigest}). */
    public static final String PREFIX = "h1:";

    /** The hex chars after {@link #PREFIX} (16 bytes). */
    public static final int HEX_CHARS = 32;

    /** The domain tag hashed ahead of the client id. */
    private static final String DOMAIN_TAG = "cc-author-v1:";

    private CidDigest() {
    }

    /** The digest of {@code clientId}; a null id digests the empty string (a key is never null). */
    public static String of(String clientId) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException broken) {
            throw new IllegalStateException("cid digest: the JRE has no SHA-256", broken);
        }
        byte[] full = sha256.digest((DOMAIN_TAG + (clientId == null ? "" : clientId)).getBytes(StandardCharsets.UTF_8));
        return PREFIX + HexFormat.of().formatHex(full, 0, HEX_CHARS / 2);
    }

    /** True when {@code s} is exactly {@value #PREFIX} then {@value #HEX_CHARS} lowercase hex chars (no folding here:
     *  a caller that accepts typed text folds it first). */
    public static boolean isDigest(String s) {
        if (s == null || s.length() != PREFIX.length() + HEX_CHARS || !s.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** {@code key} without its {@value #PREFIX} tag when it carries one, else {@code key} unchanged (null = ""): the
     *  characters a shortened display shows, so every shown character is a digest character. */
    public static String body(String key) {
        String k = key == null ? "" : key;
        return k.startsWith(PREFIX) ? k.substring(PREFIX.length()) : k;
    }
}
