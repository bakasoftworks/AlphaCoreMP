package cc.starsector.server.runtime;

import cc.starsector.server.match.MatchDescriptors;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The per-address LIVE-SEAT allowance ({@code moderation.maxSeatsPerSource}): how many pilot seats one
 * internet address may hold at once on a PASSWORD door — {@code 0} = no limit, {@code N} = at most N
 * (the shipped default is the serve resolver's, {@code 1}). A household behind one router shares one
 * address, so it counts as one; spectators never count; a same-cid takeover never counts against itself.
 * The allowance is a FAIRNESS lever for a public lobby, never a security boundary — the squat eviction
 * law stays the answer to silent claims, and a capped joiner causes no ledger, authority or squat change.
 *
 * <p>THIS BOX IS EXEMPT: a source equal to loopback ({@code 127.0.0.1} / {@code ::1} exactly — never
 * 127/8, so a loopback ALIAS such as {@code 127.0.0.2} is a stranger to the rows), to any address of an up
 * non-loopback interface at bind, or to the address the door advertises. The operator may name more
 * exempt addresses in {@code moderation.sourceCapExempt} ({@link #parseExempt}: literals only): a VPN
 * hairpin can reach the door from the tunnel's gateway address, which no interface of this box holds, so
 * without the row the host's own second window would be refused. The test is on the
 * {@link InetAddress} (byte equality), never on the /56 source key: {@code ::1}'s key is a whole /56.
 * The door prints the bound set once at open, configured entries included (an exemption a relay, a VPN
 * hairpin or the row would widen is visible, never silent). A session from an exempt address is not
 * counted toward anyone's total either — the host's own windows are free seats, even when a LAN joiner
 * shares their IPv6 /56 key.
 *
 * <p>Immutable; {@link #boundTo} returns the door's copy with the bind-time addresses folded in on top of
 * the configured ones.
 */
public final class SourceSeatCap {

    /** The largest allowance a row may name — one seat per descriptor seat, {@link MatchDescriptors#MAX_SEATS}. */
    public static final int MAX = MatchDescriptors.MAX_SEATS;

    /** The config key as the operator reads it (the file row; {@code cc.} / env twins in the resolver). */
    public static final String ROW = "moderation.maxSeatsPerSource";

    /** The exempt-address row as the operator reads it (the file row; {@code cc.} / env twins in the resolver). */
    public static final String ROW_EXEMPT = "moderation.sourceCapExempt";

    /** The most entries the exempt row may name, counted before duplicates fold (a bounded boot parse). */
    public static final int MAX_EXEMPT = 64;

    /** The longest address literal an entry may hold: a full IPv6 text with an IPv4 tail is 45 chars. */
    private static final int MAX_LITERAL = 45;

    /** No allowance: the door admits as before the lever existed (byte-identical admission). */
    public static final SourceSeatCap OFF = new SourceSeatCap(0, List.of());

    private static final InetAddress LOOPBACK_V4;
    private static final InetAddress LOOPBACK_V6;

    static {
        try {
            LOOPBACK_V4 = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            byte[] v6 = new byte[16];
            v6[15] = 1;
            LOOPBACK_V6 = InetAddress.getByAddress(v6);
        } catch (UnknownHostException impossible) {
            throw new AssertionError("a literal loopback address never fails to build", impossible);
        }
    }

    private final int cap;
    /** Byte-equality members ({@code Inet4Address} / {@code Inet6Address} equals ignore scope); insertion order = the print order. */
    private final Set<InetAddress> exempt;

    private SourceSeatCap(int cap, Collection<InetAddress> exempt) {
        this.cap = cap;
        this.exempt = new LinkedHashSet<>(exempt);
    }

    /**
     * LOUD parse of the row's value: 1..3 ASCII digits naming 0..{@link #MAX}; anything else THROWS so the
     * boot refuses (the same grammar the launcher's pre-write check refuses). The echo is bounded to 32 chars.
     */
    public static int parseCap(String raw) {
        String t = raw == null ? "" : raw.trim();
        boolean digits = !t.isEmpty() && t.length() <= 3;
        for (int i = 0; digits && i < t.length(); i++) {
            char c = t.charAt(i);
            digits = c >= '0' && c <= '9';
        }
        if (!digits || Integer.parseInt(t) > MAX) {
            String echo = t.length() > 32 ? t.substring(0, 32) + "..." : t;
            throw new IllegalArgumentException(ROW + " must be a whole number from 0 to " + MAX
                    + ", got '" + echo + "'");
        }
        return Integer.parseInt(t);
    }

    /**
     * LOUD parse of the exempt row: comma-separated LITERAL addresses — dotted-quad IPv4 ({@code 10.2.0.1}) or
     * IPv6 ({@code fd00::1}, bracketed {@code [fd00::1]} accepted too). Blanks around an entry are trimmed, a
     * blank row is the empty list, duplicates fold (the first spelling keeps its place in the print order).
     * Anything else THROWS naming the bad entry (echo bounded to 32 chars), so the boot refuses: an empty entry
     * (a stray or trailing comma), more than {@link #MAX_EXEMPT} entries, a leading-zero octet, an IPv6 zone
     * suffix, and any HOSTNAME. The literal test runs on the characters BEFORE any {@link InetAddress} call,
     * so a config row never causes a DNS lookup on the serving box: an IPv4 entry is built from its four octets
     * by hand, and an IPv6 entry (hex digits, colons and dots only) reaches {@link InetAddress#getByName} in
     * brackets, the form the JDK parses as a literal or refuses without resolving.
     *
     */
    public static List<InetAddress> parseExempt(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            return List.of();
        }
        String[] entries = t.split(",", -1);
        if (entries.length > MAX_EXEMPT) {
            throw new IllegalArgumentException(ROW_EXEMPT + " names at most " + MAX_EXEMPT + " addresses, got "
                    + entries.length);
        }
        Set<InetAddress> out = new LinkedHashSet<>();
        for (String entry : entries) {
            out.add(parseLiteral(entry.trim()));
        }
        return List.copyOf(out);
    }

    /**
     * One trimmed exempt entry: an IPv4 dotted quad, or an IPv6 literal (optionally bracketed); else throws.
     *
     */
    private static InetAddress parseLiteral(String entry) {
        String v = entry;
        boolean bracketed = v.length() >= 2 && v.charAt(0) == '[' && v.charAt(v.length() - 1) == ']';
        if (bracketed) {
            v = v.substring(1, v.length() - 1);
        }
        if (v.isEmpty()) {
            throw badEntry(entry, "an empty entry (a stray or trailing comma)", null);
        }
        if (v.length() > MAX_LITERAL) {
            throw badEntry(entry, "longer than any address literal", null);
        }
        if (v.indexOf(':') < 0) {
            byte[] quad = bracketed ? null : dottedQuad(v);
            if (quad == null) {
                throw badEntry(entry, "not an IPv4 dotted quad or an IPv6 literal (hostnames are refused)", null);
            }
            try {
                return InetAddress.getByAddress(quad);
            } catch (UnknownHostException impossible) {
                throw new AssertionError("a four-byte address never fails to build", impossible);
            }
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean literal = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == ':' || c == '.';
            if (!literal) {
                throw badEntry(entry, c == '%' ? "an IPv6 zone suffix (drop it: the match is on the address bytes)"
                        : "not an IPv6 literal (hostnames are refused)", null);
            }
        }
        try {
            return InetAddress.getByName("[" + v + "]");
        } catch (UnknownHostException notLiteral) {
            throw badEntry(entry, "not a valid IPv6 literal", notLiteral);
        }
    }

    /**
     * Four dot-separated decimal octets 0..255, no sign, no leading zero (never octal-ambiguous); else null.
     *
     */
    private static byte[] dottedQuad(String v) {
        String[] parts = v.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
                return null;
            }
            int n = 0;
            for (int k = 0; k < p.length(); k++) {
                char c = p.charAt(k);
                if (c < '0' || c > '9') {
                    return null;
                }
                n = n * 10 + (c - '0');
            }
            if (n > 255) {
                return null;
            }
            out[i] = (byte) n;
        }
        return out;
    }

    /** The refusal the boot surfaces, naming the entry (operator config, never player text). */
    private static IllegalArgumentException badEntry(String entry, String why, Throwable cause) {
        String echo = entry.length() > 32 ? entry.substring(0, 32) + "..." : entry;
        return new IllegalArgumentException(ROW_EXEMPT + " entry '" + echo + "' refused: " + why
                + "; the row takes literal IPv4 / IPv6 addresses, comma-separated", cause);
    }

    /** The unbound allowance with no configured exemptions: exactly {@code of(cap, List.of())}. */
    public static SourceSeatCap of(int cap) {
        return of(cap, List.of());
    }

    /**
     * The unbound allowance: {@code cap} 0 = {@link #OFF} (a configured list exempts nothing when nothing is
     * capped); else the cap with loopback already exempt, then the operator's {@code configuredExempt} entries
     * (the {@link #parseExempt} result; null entries skipped, at most {@link #MAX_EXEMPT}).
     *
     */
    public static SourceSeatCap of(int cap, Collection<InetAddress> configuredExempt) {
        if (cap < 0 || cap > MAX) {
            throw new IllegalArgumentException(ROW + " out of range 0.." + MAX + ": " + cap);
        }
        Collection<InetAddress> configured = configuredExempt == null ? List.of() : configuredExempt;
        if (configured.size() > MAX_EXEMPT) {
            throw new IllegalArgumentException(ROW_EXEMPT + " names at most " + MAX_EXEMPT + " addresses, got "
                    + configured.size());
        }
        if (cap == 0) {
            return OFF;
        }
        List<InetAddress> all = new ArrayList<>(2 + configured.size());
        all.add(LOOPBACK_V4);
        all.add(LOOPBACK_V6);
        for (InetAddress a : configured) {
            if (a != null) {
                all.add(a);
            }
        }
        return new SourceSeatCap(cap, all);
    }

    /**
     * The door's copy: this allowance plus the bind-time exemptions — the advertised address and every
     * address of every up non-loopback interface ({@code BindAddressPick.upNonLoopbackAddresses()}).
     * {@link #OFF} stays {@link #OFF}. Null entries are skipped.
     */
    public SourceSeatCap boundTo(InetAddress advertisedOrNull, Collection<InetAddress> hostAddresses) {
        if (!armed()) {
            return OFF;
        }
        List<InetAddress> all = new ArrayList<>(exempt);
        if (advertisedOrNull != null) {
            all.add(advertisedOrNull);
        }
        for (InetAddress a : hostAddresses) {
            if (a != null) {
                all.add(a);
            }
        }
        return new SourceSeatCap(cap, all);
    }

    /** {@code true} when a cap applies ({@code cap > 0}). */
    public boolean armed() {
        return cap > 0;
    }

    /** The allowance (0 = off). */
    public int cap() {
        return cap;
    }

    /** {@code true} for a source that is never counted nor capped (this box). A null address is not exempt. */
    public boolean exempt(InetAddress address) {
        return address != null && exempt.contains(address);
    }

    /**
     * The boot-line text: {@code off}, or {@code max=N exempt=[a, b, ...]} in insertion order — loopback, the
     * configured {@link #ROW_EXEMPT} entries, then the bind-time host addresses (all the operator's own).
     */
    public String describe() {
        if (!armed()) {
            return "off";
        }
        StringBuilder sb = new StringBuilder(64).append("max=").append(cap).append(" exempt=[");
        boolean first = true;
        for (InetAddress a : exempt) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(a.getHostAddress());
        }
        return sb.append(']').toString();
    }

    @Override
    public String toString() {
        return "SourceSeatCap[" + describe() + "]";
    }
}
