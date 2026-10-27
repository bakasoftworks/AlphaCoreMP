package cc.starsector.server.ops;

import java.util.List;

/**
 * Package mode's open-time Setup prefills: the bind pair a package's console opens on and the
 * Advertise placeholder it shows until the host types the address joiners dial. Pure helpers over
 * the config's lines; {@link ServerOpsCore} (the console's config answer) and
 * {@link ServerOpsLaunch#packageBrickEndpoint} (the placeholder refusal) read them.
 *
 */
final class OpsSetupPrefill {

    /** The config rows a package's bind fields prefill from ({@link #packageBindPrefill}). */
    static final String PKG_BIND_HOST_KEY = "server.bindHost";
    static final String PKG_BIND_PORT_KEY = "server.bindPort";

    /** The Advertise placeholder a package shows in place of a lease's exit IP (the web
     * console's {@code setupAdvertisePrefix} default, {@code OpsConsoleJsSetup}). */
    static final String PKG_PUBLIC_IP = "<public IP>";

    private OpsSetupPrefill() {
    }

    /**
     * Package mode's bind-field prefill
     * reads the config's {@code server.bindHost} row (blank / absent =
     * {@link PackageBind#WILDCARD}) and {@code server.bindPort} row (blank / absent = the owner-mode
     * default {@link ServerOpsCore#DEFAULT_BIND_PORT}). WHY: a package's launcher has blank box
     * bind rows, so ITS config's rows rule the bind ({@link ServeLauncher}'s box-row javadoc: "as in
     * a stranger package") - the rows ARE the ruling pair, and owner mode's {@code 127.0.0.1}
     * literal is a same-PC-only bind. Last row wins (the row-reader idiom the other prefills use).
     * Pure; the port text is unchecked here ({@link PackageBind} validates it at Start).
     *
     * @return {@code [host, port]}, both trimmed
     */
    static String[] packageBindPrefill(List<String> propsLines) {
        String host = null;
        String port = null;
        for (String line : propsLines) {
            if (line.startsWith(PKG_BIND_HOST_KEY + "=")) {
                host = line.substring(PKG_BIND_HOST_KEY.length() + 1).trim();
            } else if (line.startsWith(PKG_BIND_PORT_KEY + "=")) {
                port = line.substring(PKG_BIND_PORT_KEY.length() + 1).trim();
            }
        }
        return new String[] {host == null || host.isEmpty() ? PackageBind.WILDCARD : host,
                port == null || port.isEmpty() ? ServerOpsCore.DEFAULT_BIND_PORT : port};
    }

    /**
     * Package mode's Advertise text,
     * {@code "<public IP>:" + port.trim()} - the page re-derives the same value on a bind-port edit
     * while the field still starts with {@link #PKG_PUBLIC_IP} ({@code OpsConsoleJsSetup}).
     * WHY: a package has no lease to learn the exit IP, and the bind host ({@link PackageBind#WILDCARD}
     * or a LAN address) is not what internet joiners dial - the operator substitutes their public
     * address; a saved {@code advertise} row (the host's own address) wins over this placeholder.
     *
     * @param port the bind-port field text, unchecked (any string, blank included -
     *             {@link PackageBind} validates the port at Start); never null
     */
    static String packageAdvertise(String port) {
        return PKG_PUBLIC_IP + ":" + port.trim();
    }
}
