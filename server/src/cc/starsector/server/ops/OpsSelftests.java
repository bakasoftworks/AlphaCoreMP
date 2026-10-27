package cc.starsector.server.ops;

/**
 * The server-ops build gate ({@code OpsSelftests --selftest}): the throw-first chains (the core's
 * logic rows, the headless press rows of {@link OpsPressSelftest}, Kill's order rows, the VPN lease
 * rows) each in its own guard, then the row mains that return their failure counts; one exit at the
 * end. The web console's own contract runs beside it ({@code OpsConsole --selftest}).
 *
 */
public final class OpsSelftests {

    private OpsSelftests() {
    }

    /** A throw-first selftest chain ({@code run()} ends at its first failed row). */
    private interface SelftestChain {
        void run() throws Exception;
    }

    /** Runs one throw-first chain; its failure prints (the row's own message) and counts as 1 so the
     *  gate still runs every chain and row main after it, exiting once at the end. */
    private static int guarded(String name, SelftestChain chain) {
        try {
            chain.run();
            return 0;
        } catch (Throwable failed) {
            System.out.println("server-ops selftest: the " + name + " chain FAILED - " + failed);
            return 1;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"--selftest".equals(args[0])) {
            System.err.println("usage: OpsSelftests --selftest");
            System.exit(2);
            return;
        }
        // No row opens a window; headless keeps any AWT touch display-free on a build box.
        System.setProperty("java.awt.headless", "true");
        // The four throw-first chains each run in their own guard: one failed row prints and counts
        // instead of ending the gate before the chains and row mains after it; the count joins the one exit below.
        int thrown = guarded("core", ServerOpsCoreSelftest::run) + guarded("press", OpsPressSelftest::run)
                + guarded("kill", ServerOpsKillSelftest::run) + guarded("vpn-lease", VpnLeaseSelftest::run);
        // The row mains: the package-folder writer's three, the package match-setting Apply rows, the
        // match-shape library, the joiner-kit packer, the record rows, the reach probe rows (loopback
        // sockets), the first-start repair rows and the update check rows; each run()
        // RETURNS its failure count (their own mains exit on it).
        if (thrown + PackageZipSelftest.run() + PackageFolderWriterSelftest.run()
                + PackageFolderWriterGuardSelftest.run() + OpsMatchApplySelftest.run()
                + cc.starsector.server.match.MatchShapesSelftest.run() + KitPackerSelftest.run()
                + ServeRecordSelftest.run() + ReachProbeSelftest.run()
                + PackageRepairSelftest.run() + PackageUpdateCheckSelftest.run() != 0) {
            System.exit(1);
        }
        System.out.println("server-ops selftest PASS");
    }
}
