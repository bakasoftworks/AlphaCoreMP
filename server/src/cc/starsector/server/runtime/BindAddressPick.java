package cc.starsector.server.runtime;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The door's address decisions, ONE home: which local address a BLANK host binds, and which address a
 * {@code 0.0.0.0} (wildcard) bind ADVERTISES in its port file (a remote client needs a host it can dial, never
 * {@code 0.0.0.0}).
 *
 * <p>WHY (the server-release recon PART C #1): before this class the
 * only pick was "the first up, non-loopback, site-local IPv4" ({@code PilotIntentSocketServer.firstNonLoopbackAddress}),
 * so a stranger's rented box whose network card carries ONLY a public IPv4 could not boot on either default host: a
 * blank host refused under {@code bind.strict}, and {@code 0.0.0.0} bound, then refused because it found nothing to
 * advertise. Only a typed address booted.
 *
 * <p>The split. {@link #siteLocalIpv4} is the old rule, unchanged, and a BLANK host still binds only that — it never
 * auto-binds a public address ({@code 0.0.0.0} is the stated way to listen on every address). {@link #advertisableIpv4}
 * is the wildcard's pick: any site-local IPv4 first (a LAN box advertises exactly what it advertised before), else the
 * first IPv4 that is not loopback, link-local ({@code 169.254/16}), any-local or multicast. CGNAT {@code 100.64/10} is
 * not site-local in the JDK, so it lands in the second arm — intended, it is that box's only dialable IPv4. IPv6 is
 * never picked (never flown end to end). Strict ({@code bind.strict}, default true, read by the caller)
 * refuses with a text a stranger host reads; lenient announces once ({@link FallbackAnnounce}) and degrades to
 * loopback.
 *
 * <p>Callers: {@code PilotIntentDatagramServer} (the UDP door: {@code resolveBindAddress}, {@code probeBind},
 * {@code openInternal}) and {@code PilotIntentSocketServer.firstNonLoopbackAddress} (the TCP sibling, site-local rule
 * only). Stateless; the decision is taken once per bind.
 *
 *
 */
final class BindAddressPick {

    /** The blank-host refusal a stranger host reads (ASCII). */
    static final String BLANK_REFUSAL = "no private-network (LAN) IPv4 address on this machine, and a blank host"
            + " binds only such an address - set the host to 0.0.0.0 to listen on every address, or type the"
            + " address to bind (127.0.0.1 = this machine only)";

    /** The wildcard refusal a stranger host reads (ASCII). */
    static final String WILDCARD_REFUSAL = "0.0.0.0 bind: this machine has no IPv4 address a remote player could"
            + " dial (only loopback, link-local or IPv6 found) - type the address to bind, or 127.0.0.1 for this"
            + " machine only";

    private BindAddressPick() {
    }

    /**
     * Every address of every up, non-loopback interface, in enumeration order (any family). An {@link IOException}
     * from the interface LIST returns what was gathered so far — the swallow the old single-pass pick had — after ONE
     * {@code bind-pick nic_enum_failed} line (the exception's SimpleName only, never its message). One ADAPTER whose
     * {@code isUp} / {@code isLoopback} throws is skipped alone, with one {@code bind-pick nic_skipped} line
     * (the {@code ServerOpsLaunch.localAddresses} rule):
     * cutting the walk there would hide a later public adapter and print a refusal text that is false.
     */
    static List<InetAddress> upNonLoopbackAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                } catch (IOException adapterFailed) {
                    System.out.println("bind-pick nic_skipped err=" + adapterFailed.getClass().getSimpleName());
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    out.add(addrs.nextElement());
                }
            }
        } catch (IOException enumFailed) {
            System.out.println("bind-pick nic_enum_failed err=" + enumFailed.getClass().getSimpleName());
        }
        return out;
    }

    /** Pure: the first candidate that is a non-loopback, site-local IPv4 ({@code 10/8}, {@code 172.16/12},
     * {@code 192.168/16}), else null — the old pick's rule, unchanged. */
    static InetAddress siteLocalIpv4(List<InetAddress> candidates) {
        for (InetAddress a : candidates) {
            if (!a.isLoopbackAddress() && a instanceof Inet4Address && a.isSiteLocalAddress()) {
                return a;
            }
        }
        return null;
    }

    /** Pure: {@link #siteLocalIpv4} when non-null (a site-local anywhere in the list beats every other address,
     * whatever the order), else the first IPv4 that is not loopback, link-local, any-local or multicast — a GLOBAL one
     * first, whatever the order, then a carrier-shared one (CGNAT {@code 100.64/10}, e.g. a tailnet adapter, or
     * {@code 198.18/15}: dialable only from inside that network) — else
     * null. IPv6 is never returned. */
    static InetAddress advertisableIpv4(List<InetAddress> candidates) {
        InetAddress lan = siteLocalIpv4(candidates);
        if (lan != null) {
            return lan;
        }
        InetAddress shared = null;
        for (InetAddress a : candidates) {
            if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()
                    && !a.isAnyLocalAddress() && !a.isMulticastAddress()) {
                byte[] b = a.getAddress();
                if (!((b[0] == 100 && (b[1] & 0xc0) == 64) || ((b[0] & 0xff) == 198 && (b[1] & 0xfe) == 18))) {
                    return a;
                }
                shared = shared == null ? a : shared;
            }
        }
        return shared;
    }

    /**
     * A BLANK host's bind address: {@link #siteLocalIpv4} only. None + {@code strict} throws
     * {@link IllegalStateException} ({@link #BLANK_REFUSAL}); none + lenient announces {@code udp-door-loopback-bind}
     * once and returns loopback. {@code strict} is a SUPPLIER, here and in {@link #wildcardAdvertiseOrRefuse}, read
     * only when the pick is empty: the knob is parsed exactly where it was
     * before this class, so a typo in {@code bind.strict} never refuses a LAN box that has an address to bind.
     */
    static InetAddress blankBindOrRefuse(List<InetAddress> candidates, BooleanSupplier strict) {
        InetAddress bind = siteLocalIpv4(candidates);
        if (bind == null) {
            if (strict.getAsBoolean()) {
                throw new IllegalStateException(BLANK_REFUSAL);
            }
            FallbackAnnounce.announceOnce("udp-door-loopback-bind",
                    "no non-loopback address resolved; binding+advertising loopback");
            bind = InetAddress.getLoopbackAddress();
        }
        return bind;
    }

    /**
     * The address a {@code 0.0.0.0} bind advertises: {@link #advertisableIpv4}. None + {@code strict} throws
     * {@link IllegalStateException} ({@link #WILDCARD_REFUSAL}); none + lenient announces
     * {@code udp-door-loopback-advertise} once and returns loopback. A non-site-local pick prints ONE
     * {@code pilot-udp-door advertise_public} line (the operator's own address, the {@code bind_redirected}
     * precedent). Closes nothing — the caller owns its socket.
     */
    static InetAddress wildcardAdvertiseOrRefuse(List<InetAddress> candidates, BooleanSupplier strict) {
        InetAddress advertised = advertisableIpv4(candidates);
        if (advertised == null) {
            if (strict.getAsBoolean()) {
                throw new IllegalStateException(WILDCARD_REFUSAL);
            }
            FallbackAnnounce.announceOnce("udp-door-loopback-advertise",
                    "wildcard bind; no dialable IPv4 address; advertising loopback");
            return InetAddress.getLoopbackAddress();
        }
        if (!advertised.isSiteLocalAddress()) {
            System.out.println("pilot-udp-door advertise_public addr=" + advertised.getHostAddress()
                    + " (no private-LAN IPv4 on this box)");
        }
        return advertised;
    }
}
