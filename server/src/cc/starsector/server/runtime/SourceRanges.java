package cc.starsector.server.runtime;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * The source-ban key grammar and the prefix arithmetic a RANGE ban matches by. A stored source key is one of
 * three forms: a bare IPv4 address ({@code 1.2.3.4}, the door's own key for an IPv4 peer), an IPv4 range
 * ({@code 1.2.3.0/24}: /8 to /31, host bits zero), or an IPv6 byte key ({@code 20:1:d:b8::/32}: /16 to /64) =
 * the first {@code ceil(N/8)} address bytes, each in lowercase hex with no leading zero, joined by {@code :},
 * then {@code ::/N}, the last byte's bits past N zero. The /56 byte key IS {@link DoorSourceBudget#sourceKey}
 * for an IPv6 peer, byte for byte, so every key the door mints is a stored key and an exact ban on it still
 * matches by string equality.
 *
 * <p>WHY one stored form per range: the bans file, the listings, the receipts and {@code unban} all carry the
 * key exactly as stored, so a ban is lifted by the text the listing printed and a shared bans file compares by
 * string. {@link #isKey} is a grammar check over those forms, never an address parser. The ONE place operator
 * text is read as an address literal is {@link #canonicalOrNull}'s IPv6 provider-range convert (typed like
 * {@code 2a0b:f4c0::/32}): a charset gate first ({@code 0-9 a-f :} only, at most {@value #RFC_MAX_LITERAL}
 * chars before the slash), then the bracketed literal through {@link InetAddress#getByName}, which checks the
 * format and never resolves a name ({@link SourceSeatCap} reads its exempt entries the same way).
 *
 * <p>Raw addresses arrive as {@link InetAddress#getAddress} bytes: 4 for an IPv4 peer (an IPv4-mapped IPv6
 * peer arrives as IPv4), 16 for an IPv6 peer; a key of one family never covers an address of the other.
 * Pure statics, no state, any thread.
 *
 */
final class SourceRanges {

    /** The shortest IPv4 range (a /8); a bare address is the /32. */
    static final int V4_MIN_BITS = 8;
    /** The door's IPv6 key length: an IPv6 peer's exact key is its /56 ({@link DoorSourceBudget#sourceKey}). */
    static final int V6_DOOR_BITS = 56;
    /** The shortest IPv6 range (a /16) and the longest (a /64, one subnet). */
    static final int V6_MIN_BITS = 16;
    static final int V6_MAX_BITS = 64;
    /** The longest typed token {@link #canonicalOrNull} reads (chars) — checked before any other work. */
    static final int MAX_TYPED_CHARS = 60;
    /** The longest IPv6 literal before the slash (eight 4-digit groups and 7 colons). */
    static final int RFC_MAX_LITERAL = 39;

    private SourceRanges() {
    }

    /** One parsed stored key: the family, the prefix bytes (4 for IPv4, {@code ceil(bits/8)} for IPv6), the length. */
    private record Prefix(boolean v6, byte[] bytes, int bits) {
    }

    /** True when {@code s} is a stored source key in one of the three forms (grammar only; never resolves). */
    static boolean isKey(String s) {
        return keyOrNull(s) != null;
    }

    /** True when {@code key} is a key the door itself mints for a peer (a bare IPv4 address or an IPv6 /56 key). */
    static boolean isDoorKey(String key) {
        Prefix p = keyOrNull(key);
        return p != null && p.bits == (p.v6 ? V6_DOOR_BITS : 32);
    }

    /** True when {@code key} is a stored key no door key equals, so only a prefix compare can match it: an IPv4
     *  range, or an IPv6 key of any length but the door's /56. */
    static boolean isRange(String key) {
        Prefix p = keyOrNull(key);
        return p != null && p.bits != (p.v6 ? V6_DOOR_BITS : 32);
    }

    /** True when {@code key} is a stored IPv6 key. */
    static boolean isIpv6Key(String key) {
        Prefix p = keyOrNull(key);
        return p != null && p.v6;
    }

    /** The prefix length of a stored key (32 for a bare IPv4 address), or -1 when {@code key} is none. */
    static int bits(String key) {
        Prefix p = keyOrNull(key);
        return p == null ? -1 : p.bits;
    }

    /** True when the stored {@code key} covers the address {@code raw} (4 or 16 bytes): same family and the first
     *  {@code bits(key)} bits equal. A key that is not a stored key, a null raw, or a raw of the other family =
     *  false. */
    static boolean covers(String key, byte[] raw) {
        Prefix p = keyOrNull(key);
        if (p == null || raw == null || raw.length != (p.v6 ? 16 : 4)) {
            return false;
        }
        int full = p.bits / 8;
        for (int i = 0; i < full; i++) {
            if (p.bytes[i] != raw[i]) {
                return false;
            }
        }
        int rest = p.bits % 8;
        return rest == 0 || (p.bytes[full] & 0xFF) == (raw[full] & highMask(rest));
    }

    /** The loopback ranges no operator or file intake bans: IPv4 127.0.0.0/8 and the IPv6 ::/16 byte key. */
    private static final String V4_LOOPBACK = "127.0.0.0/8";
    private static final String V6_LOOPBACK = "0:0::/16";

    /**
     * True when the stored {@code key} lies WITHIN loopback: {@value #V4_LOOPBACK} or {@value #V6_LOOPBACK}
     * {@link #covers} the key's own prefix bytes, zero-padded to a full address. A coverage test, never an equality:
     * {@code 127.0.0.0/24}, {@code 127.1.2.3} and the door key of {@code ::1} all lie within. Every stored key is
     * at least as narrow as these two (/8 and /16 are the widest lengths a key takes), so covering its padded prefix
     * IS lying within. WHY: a loopback bar refuses the host's own windows; the ONE predicate the typed-key
     * normalizer ({@link #canonicalOrNull}), the bans file parse ({@code BanSet.parse}) and the boot hold re-install
     * ({@code DoorSourceHold.hydrate}) share. The door's own add stays open to it (a rig bans a loopback range
     * directly).
     */
    static boolean isLoopbackKey(String key) {
        Prefix p = keyOrNull(key);
        return p != null && covers(p.v6 ? V6_LOOPBACK : V4_LOOPBACK, Arrays.copyOf(p.bytes, p.v6 ? 16 : 4));
    }

    /** The refusal {@link #canonicalOrNull} appends for a loopback key (the server's own address). */
    static final String LOOPBACK_REFUSAL = "loopback is never banned (the server's own address)";

    /** The stored key of {@code raw} (4 or 16 bytes) masked to {@code bits} — the range index's lookup form
     *  ({@code bits} 8..32 for IPv4, 32 = the bare address; 16..64 for IPv6), or null when out of range. */
    static String maskedKey(byte[] raw, int bits) {
        if (raw == null || (raw.length != 4 && raw.length != 16)) {
            return null;
        }
        boolean v6 = raw.length == 16;
        if (v6 ? bits < V6_MIN_BITS || bits > V6_MAX_BITS : bits < V4_MIN_BITS || bits > 32) {
            return null;
        }
        byte[] b = Arrays.copyOf(raw, v6 ? (bits + 7) / 8 : 4);
        maskInPlace(b, bits);
        return render(v6, b, bits);
    }

    /**
     * The one typed-input normalizer: an operator's address or range → its stored key, or null with the reason
     * appended to {@code whyOrNull} (plain operator text naming the form that would be accepted; it never quotes
     * the typed token, only a key derived from it). Accepted: any stored key as-is (bare IPv4, a canonical IPv4
     * range, an IPv6 byte key as a bans listing prints it), and an IPv6 provider range in full IPv6 form
     * ({@code 2a0b:f4c0::/32}), converted to its byte key ({@code 2a:b:f4:c0::/32}). A token shaped like a byte
     * key (every group one or two hex digits, ending {@code ::/N}) is judged as a byte key only, never re-read as
     * an IPv6 literal: a mistyped key copied from a listing must not silently ban a different range. ASCII
     * letters fold to lowercase first; any other character outside printable ASCII refuses.
     * A key that lies within loopback ({@link #isLoopbackKey}) refuses with
     * {@link #LOOPBACK_REFUSAL}, whichever form it was typed in.
     */
    static String canonicalOrNull(String typed, StringBuilder whyOrNull) {
        String key = storedFormOrNull(typed, whyOrNull);
        return key != null && isLoopbackKey(key) ? refuse(whyOrNull, LOOPBACK_REFUSAL) : key;
    }

    /** {@link #canonicalOrNull} before its loopback refusal: the typed token's stored key, or null + the reason. */
    private static String storedFormOrNull(String typed, StringBuilder whyOrNull) {
        if (typed == null || typed.isEmpty()) {
            return refuse(whyOrNull, "no address given");
        }
        if (typed.length() > MAX_TYPED_CHARS) {
            return refuse(whyOrNull, "longer than any address or range");
        }
        char[] folded = new char[typed.length()];
        for (int i = 0; i < folded.length; i++) {
            char c = typed.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return refuse(whyOrNull, "not an address or range (a character no address has)");
            }
            folded[i] = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
        }
        String t = new String(folded);
        if (isKey(t)) {
            return t;
        }
        int slash = t.indexOf('/');
        String head = slash < 0 ? t : t.substring(0, slash);
        if (slash < 0) {
            return refuse(whyOrNull, head.indexOf(':') >= 0
                    ? "one IPv6 address is no ban key: add a range length, e.g. /56 (one home connection) or /32"
                            + " (a provider)"
                    : "not an IPv4 address (four numbers 0-255, no leading zeros)");
        }
        int bits = lengthOrMinus(t, slash + 1);
        if (bits < 0) {
            return refuse(whyOrNull, "the range length after / must be a number");
        }
        if (head.indexOf(':') < 0) {
            return v4RefusalOrNull(head, bits, whyOrNull);
        }
        if (bits < V6_MIN_BITS || bits > V6_MAX_BITS) {
            return refuse(whyOrNull, "an IPv6 range takes /" + V6_MIN_BITS + " to /" + V6_MAX_BITS);
        }
        byte[] keyBytes = byteKeyShapeOrNull(head);
        if (keyBytes != null) {
            int want = (bits + 7) / 8;
            if (keyBytes.length != want || !canonicalGroups(head)) {
                return refuse(whyOrNull, "a /" + bits + " key from the bans listing is exactly " + want
                        + " bytes in hex with no leading zeros - copy the key whole");
            }
            byte[] masked = keyBytes.clone();
            maskInPlace(masked, bits);
            return refuse(whyOrNull, "bits past /" + bits + " are set: the range is " + render(true, masked, bits));
        }
        return rfcV6OrNull(head, bits, whyOrNull);
    }

    /** {@link #canonicalOrNull}'s IPv4 arm for a token that is no stored key: always null + the reason. */
    private static String v4RefusalOrNull(String head, int bits, StringBuilder whyOrNull) {
        byte[] quad = dottedOrNull(head);
        if (quad == null) {
            return refuse(whyOrNull, "not an IPv4 address (four numbers 0-255, no leading zeros)");
        }
        if (bits == 32) {
            return refuse(whyOrNull, "a /32 is one address: type it bare as " + render(false, quad, 32));
        }
        if (bits < V4_MIN_BITS || bits > 32) {
            return refuse(whyOrNull, "an IPv4 range takes /" + V4_MIN_BITS + " to /31");
        }
        maskInPlace(quad, bits);
        return refuse(whyOrNull, "host bits are set: the range is " + render(false, quad, bits));
    }

    /**
     * The IPv6 provider-range convert, the only literal parse: the charset gate ({@code 0-9 a-f :}, no {@code .}
     * and no {@code %}, at most {@value #RFC_MAX_LITERAL} chars), then the bracketed literal through
     * {@link InetAddress#getByName} (format check only: a bracketed host is never looked up), then the byte key.
     */
    private static String rfcV6OrNull(String head, int bits, StringBuilder whyOrNull) {
        if (head.length() > RFC_MAX_LITERAL) {
            return refuse(whyOrNull, "longer than any IPv6 address");
        }
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == ':')) {
                return refuse(whyOrNull, "not an IPv6 range (only 0-9, a-f and : before the /)");
            }
        }
        byte[] raw;
        try {
            raw = InetAddress.getByName("[" + head + "]").getAddress();
        } catch (UnknownHostException notLiteral) {
            // the refusal IS the outcome: the caller echoes this reason once on its rejected line
            return refuse(whyOrNull, "not a valid IPv6 range");
        }
        if (raw.length != 16) {
            return refuse(whyOrNull, "an IPv4-mapped IPv6 range: type the IPv4 range instead");
        }
        byte[] masked = raw.clone();
        maskInPlace(masked, bits);
        String key = render(true, Arrays.copyOf(masked, (bits + 7) / 8), bits);
        if (!Arrays.equals(raw, masked)) {
            return refuse(whyOrNull, "bits past /" + bits + " are set: the range is " + key);
        }
        return key;
    }

    // ---------------------------------------------------------------- grammar

    private static Prefix keyOrNull(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_TYPED_CHARS) {
            return null;
        }
        int slash = s.indexOf('/');
        if (slash < 0) {
            byte[] quad = dottedOrNull(s);
            return quad == null ? null : new Prefix(false, quad, 32);
        }
        int bits = lengthOrMinus(s, slash + 1);
        if (bits < 0) {
            return null;
        }
        String head = s.substring(0, slash);
        if (head.endsWith("::")) {
            if (bits < V6_MIN_BITS || bits > V6_MAX_BITS || !canonicalGroups(head)) {
                return null;
            }
            byte[] b = byteKeyShapeOrNull(head);
            if (b == null || b.length != (bits + 7) / 8 || !hostBitsZero(b, bits)) {
                return null;
            }
            return new Prefix(true, b, bits);
        }
        if (bits < V4_MIN_BITS || bits > 31) {
            return null;
        }
        byte[] quad = dottedOrNull(head);
        return quad == null || !hostBitsZero(quad, bits) ? null : new Prefix(false, quad, bits);
    }

    /** The bytes of a byte-key-shaped head ({@code g:g:...::}, every group 1..2 lowercase hex digits), any count;
     *  else null. Leading zeros are allowed HERE (shape only); {@link #canonicalGroups} judges them. */
    private static byte[] byteKeyShapeOrNull(String head) {
        if (!head.endsWith("::") || head.length() < 3) {
            return null;
        }
        String[] groups = head.substring(0, head.length() - 2).split(":", -1);
        byte[] b = new byte[groups.length];
        for (int i = 0; i < groups.length; i++) {
            String g = groups[i];
            if (g.isEmpty() || g.length() > 2) {
                return null;
            }
            int v = 0;
            for (int k = 0; k < g.length(); k++) {
                char c = g.charAt(k); // ASCII only: Character.digit would take other scripts' digits
                int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
                if (d < 0) {
                    return null;
                }
                v = v * 16 + d;
            }
            b[i] = (byte) v;
        }
        return b;
    }

    /** True when no group of a byte-key head carries a leading zero (the door's {@code toHexString} form). */
    private static boolean canonicalGroups(String head) {
        for (String g : head.substring(0, head.length() - 2).split(":", -1)) {
            if (g.length() == 2 && g.charAt(0) == '0') {
                return false;
            }
        }
        return true;
    }

    /** Four dot-separated decimal parts 0..255, no sign, no leading zero (never octal-ambiguous); else null. */
    private static byte[] dottedOrNull(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] quad = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
                return null;
            }
            int v = 0;
            for (int k = 0; k < p.length(); k++) {
                char c = p.charAt(k);
                if (c < '0' || c > '9') {
                    return null;
                }
                v = v * 10 + (c - '0');
            }
            if (v > 255) {
                return null;
            }
            quad[i] = (byte) v;
        }
        return quad;
    }

    /** The range length at {@code s[from..]}: 1..2 decimal digits, no leading zero; else -1. */
    private static int lengthOrMinus(String s, int from) {
        int n = s.length() - from;
        if (n < 1 || n > 2 || (n == 2 && s.charAt(from) == '0')) {
            return -1;
        }
        int v = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    // ---------------------------------------------------------------- bits

    /** The byte mask keeping the high {@code keep} bits (1..8). */
    private static int highMask(int keep) {
        return (0xFF << (8 - keep)) & 0xFF;
    }

    private static boolean hostBitsZero(byte[] b, int bits) {
        for (int i = 0; i < b.length; i++) {
            int keep = Math.max(0, Math.min(8, bits - 8 * i));
            int mask = keep == 0 ? 0 : highMask(keep);
            if ((b[i] & ~mask & 0xFF) != 0) {
                return false;
            }
        }
        return true;
    }

    private static void maskInPlace(byte[] b, int bits) {
        for (int i = 0; i < b.length; i++) {
            int keep = Math.max(0, Math.min(8, bits - 8 * i));
            b[i] = (byte) (b[i] & (keep == 0 ? 0 : highMask(keep)));
        }
    }

    /** The stored text of prefix bytes {@code b} at {@code bits}: dotted (+ {@code /N} below 32) or the byte key
     *  ({@code ceil(bits/8)} bytes of {@code b}, each {@link Integer#toHexString}, then {@code ::/N}). */
    private static String render(boolean v6, byte[] b, int bits) {
        StringBuilder out = new StringBuilder(32);
        if (!v6) {
            for (int i = 0; i < 4; i++) {
                if (i > 0) {
                    out.append('.');
                }
                out.append(b[i] & 0xFF);
            }
            return bits == 32 ? out.toString() : out.append('/').append(bits).toString();
        }
        int n = (bits + 7) / 8;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(Integer.toHexString(b[i] & 0xFF));
        }
        return out.append("::/").append(bits).toString();
    }

    private static String refuse(StringBuilder whyOrNull, String reason) {
        if (whyOrNull != null) {
            whyOrNull.append(reason);
        }
        return null;
    }
}
