package cc.starsector.server.ops;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Package mode's Start bind decision, used by
 * BOTH shells. WHY: a stranger's host has no VPN lease script, so there is no
 * lease pre-step and no {@link ServerOpsLaunch#resolveStartBind} (owner mode, untouched); what the
 * operator typed (prefilled from the config's {@code server.bindHost} / {@code server.bindPort})
 * is checked so a Start never binds an address this box does not hold: {@code 0.0.0.0} = every
 * interface, a loopback host (typed spelling OR resolved address) is ACCEPTED as typed, any other
 * host must sit on a local interface.
 * WHY loopback passes (it was refused):
 * a same-PC test is a real host use (a joiner on the same machine dials 127.0.0.1), and a
 * stranger's {@code serve.bat} already serves a loopback bind ({@link ServeLauncher} applies no
 * loopback refusal) - the console refusing what the batch file allows was a release cut. Loopback is always held, so it skips the
 * interface check. Pure: the address set is the caller's ({@link ServerOpsLaunch#localAddresses} live,
 * a fixture set in the rows). {@link ServerOpsLaunch#validateBind} runs first (one non-blank
 * token; port 1..65535, 0/auto refused).
 */
public final class PackageBind {

    /** The every-interface host a blank field means. */
    static final String WILDCARD = "0.0.0.0";

    private PackageBind() {
    }

    /** @return the trimmed host (blank = {@link #WILDCARD}) + port, no advertise (no lease)
     * @throws IllegalArgumentException the refusal sentence the shell shows (a dialog / JSON error) */
    public static ServerOpsLaunch.StartBind resolve(String typedHost, String typedPort,
            Set<String> localAddresses) {
        String host = typedHost == null ? "" : typedHost.trim();
        String port = typedPort == null ? "" : typedPort.trim();
        if (host.isEmpty()) {
            host = WILDCARD;
        }
        ServerOpsLaunch.validateBind(host, port);
        if (WILDCARD.equals(host)) {
            return new ServerOpsLaunch.StartBind(host, port, null);
        }
        String canonical = loopback(host) ? host : ServerOpsLaunch.canonicalHost(host);
        if (loopback(canonical)) {
            return new ServerOpsLaunch.StartBind(host, port, null); // a same-PC serve
        }
        if (!localAddresses.contains(canonical)) {
            List<String> v4 = localAddresses.stream().filter(a -> !a.contains(":")).sorted().toList();
            throw new IllegalArgumentException("bind host " + host + " is not on any local"
                    + " interface; local IPv4: " + (v4.isEmpty() ? "(none)" : String.join(", ", v4))
                    + " - type a host this box holds");
        }
        return new ServerOpsLaunch.StartBind(host, port, null);
    }

    /** The typed loopback spellings + {@code ::1}'s {@code getHostAddress} form (the resolved leg). */
    private static boolean loopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        // Only a dotted-quad 127 literal skips the checks, so no name can carry a backslash into the written row.
        return h.matches("127(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}") || "localhost".equals(h) || "::1".equals(h)
                || "0:0:0:0:0:0:0:1".equals(h);
    }
}
