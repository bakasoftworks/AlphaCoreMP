package cc.starsector.client;

import cc.starsector.client.net.ServerSnapshotClient;
import cc.starsector.server.client.PilotIntentClientSession;

/**
 * The legacy pre-predict boot: the faithful-pilot watch session opened at
 * application load on its {@code cc-pilot-connect} daemon thread, and the
 * {@link ServerSnapshotClient} constructor the shared-menu / per-combat snapshot lanes use.
 *
 * <p>WHY a class of its own: these two are the proven pre-predict regression baseline and share
 * nothing with the predict dial except the port-file reader ({@link ClientLaunchKnobs}); they
 * were homed in {@link CcClientModPlugin} (2612 non-blank lines, no tier) for convenience only.
 * Moved VERBATIM (bodies, javadoc, thread name, daemon flag, every log line). The session
 * REGISTRY stays in the plugin — {@link CcClientModPlugin#pilotSession()} is what the combat
 * plugins read; {@code connectPilotSession} publishes to that same volatile field at the same
 * point, in the same order. The plugin keeps the public {@code newServerClient()} delegate.
 *
 * <p>Carved out of the client-mod plugin,
 * following the {@code JoinGuiSelftest} and
 * {@code CtrlFanout} carve precedents.
 *
 *
 */
final class PilotSessionBoot {

    private PilotSessionBoot() {
    }

    /**
     * pilot-session stream-acquire retry budget: how many times to retry
     * {@code socket.getOutputStream()} on the one live socket, and the backoff between attempts.
     * 240 × 250ms ≈ 60s spans app-load → menu comfortably so a denial that clears when the load
     * phase ends / the cold output-stream class is warmed lands within budget; if it never clears,
     * the WATCH log shows 240 denials (decisive negative). Only the in-game pilot connect uses these;
     * off-engine callers acquire on attempt 1.
     */
    private static final int STREAM_ACQUIRE_ATTEMPTS = 240;
    private static final long STREAM_ACQUIRE_BACKOFF_MILLIS = 250L;

    /**
     * Open the faithful-pilot watch session at application load — outside combat's script
     * SecurityManager — so the in-combat {@link PilotIntentCombatPlugin} consumes an already-open socket
     * instead of opening one during combat (which the script classloader {@code B} denies). Resolves the
     * pilot endpoint + ship id + weapon group from the same {@code cc.pilot.*} / {@code CC_PILOT_*}
     * properties the combat plugin used to read, then connects digest-free (the SHA-256 is a test-only
     * off-engine checksum, never on the wire). Fail-closed: any error leaves {@link CcClientModPlugin#pilotSession()}
     * {@code null} and the mission loads with locally-controlled ships.
     */
    static void openPilotSession() {
        // this is the FIRST PilotWatchLog call on every launch, so it force-loads the logger
        // here at application load — outside the combat script SecurityManager — pre-warming it for the
        // in-combat plugin (see PilotWatchLog).
        String[] endpoint = ClientLaunchKnobs.resolvePilotEndpoint();
        if (endpoint == null) {
            PilotWatchLog.info("onApplicationLoad: no pilot endpoint configured "
                    + "(cc.pilot.portFile / CC_PILOT_PORT_FILE / cc.pilot.port unset) -> ordinary launch, no pilot session");
            return; // no pilot endpoint configured -> an ordinary (non-watch) launch
        }
        int port;
        try {
            port = Integer.parseInt(endpoint[1]);
        } catch (NumberFormatException e) {
            PilotWatchLog.error("onApplicationLoad: pilot endpoint port not parseable: '" + endpoint[1] + "'", e);
            return;
        }
        if (port <= 0) {
            PilotWatchLog.info("onApplicationLoad: pilot endpoint port <= 0 (" + port + ") -> no pilot session");
            return; // port 0 = unconfigured sentinel
        }
        long shipId = ClientLaunchKnobs.resolvePilotLong("cc.pilot.shipId", "CC_PILOT_SHIP_ID", 1L);
        int group = (int) ClientLaunchKnobs.resolvePilotLong("cc.pilot.weaponGroup", "CC_PILOT_WEAPON_GROUP", 0L);
        // Connect on a DEDICATED daemon thread, NOT synchronously here. The WATCH
        // proved onApplicationLoad runs UNDER the script classloader B (stack: ResourceLoaderState.init ->
        // CombatMain.main -> onApplicationLoad), so a synchronous connect's socket.getOutputStream() lazy
        // class load is denied ("File access and reflection are not allowed to scripts") — falsifying the
        // premise that app-load is outside the script SecurityManager. The proven-working
        // ServerSnapshotClient opens its socket on its OWN reader thread (never the app-load thread) and
        // succeeds; this mirrors that. The open session lands in `pilotSession` for the combat plugin.
        final String host = endpoint[0];
        final int fport = port;
        final long fshipId = shipId;
        final int fgroup = group;
        PilotWatchLog.info("onApplicationLoad: opening pilot session (background thread) host=" + host
                + " port=" + fport + " shipId=" + fshipId + " group=" + fgroup);
        Thread connectThread = new Thread(() -> connectPilotSession(host, fport, fshipId, fgroup), "cc-pilot-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /**
     * Open the pilot session OFF the application-load thread. Runs on the {@code
     * cc-pilot-connect} daemon thread so {@code socket.getOutputStream()}'s lazy class load is NOT under
     * the script classloader {@code B}'s restricted context — proven necessary by the WATCH
     * (the synchronous app-load connect was denied with {@code SecurityException: File access and
     * reflection are not allowed to scripts}) and proven sufficient by {@link ServerSnapshotClient},
     * which opens its socket on its own reader thread and is allowed. On success the open session is
     * published to {@link CcClientModPlugin#pilotSession()}; on failure it stays {@code null} (the combat plugin then leaves
     * the ships locally driven — fail-closed). The exact throwable still lands in {@code starsector.log}
     * so a residual failure (e.g. the background thread does NOT escape the script SM after
     * all) is visible, not silent.
     */
    private static void connectPilotSession(String host, int port, long shipId, int group) {
        // The TCP connect always succeeds; only the FIRST socket.getOutputStream() is denied
        // by the script classloader B (the cold output-stream-impl class load) while the app-load/combat
        // phase is active. The earlier "do it on a background thread" plan was FALSIFIED (the denial is
        // per-cold-class, not per-thread). The proven-working ServerSnapshotClient (also B-loaded) succeeds
        // because its reconnect loop keeps trying until acquisition lands in an allowed context. We adapt
        // that to THIS accept-once server: hold the one live socket and RETRY only the stream acquisition
        // (PilotIntentClientSession.connect with a retry budget), never reconnect. The observer logs WHEN
        // the denial clears — the datum the next WATCH needs to pin the mechanism (phase-lift vs warm).
        PilotIntentClientSession.StreamAcquireObserver observer = (attempt, failure, elapsedMillis) -> {
            if (failure == null) {
                PilotWatchLog.info("pilot session streams ACQUIRED on attempt " + attempt
                        + " (elapsed " + elapsedMillis + "ms) -> script-sandbox denial cleared");
            } else if (attempt == 1) {
                PilotWatchLog.error("pilot session stream acquire attempt 1 DENIED (elapsed " + elapsedMillis
                        + "ms) -> retrying on the same socket past the script-sandbox window", failure);
            }
            // Intermediate failures are intentionally not logged (would be hundreds of lines); the
            // ACQUIRED line above pins exactly which attempt / elapsed-ms the denial cleared.
        };
        try {
            // Digest-free: the test-only SHA is local (never on the wire) and its
            // MessageDigest.getInstance does reflection; omit it on the in-game path.
            CcClientModPlugin.pilotSession = PilotIntentClientSession.connect(host, port, shipId, group, false,
                    STREAM_ACQUIRE_ATTEMPTS, STREAM_ACQUIRE_BACKOFF_MILLIS, observer);
            PilotWatchLog.info("pilot session OPEN (background) -> " + host + ":" + port
                    + " shipId=" + shipId + " group=" + group);
        } catch (Throwable t) {
            PilotWatchLog.error("pilot session background connect FAILED host=" + host
                    + " port=" + port + " after " + STREAM_ACQUIRE_ATTEMPTS + " stream-acquire attempts"
                    + " -> ships left locally driven (window stays LOCAL VANILLA)", t);
            CcClientModPlugin.pilotSession = null;
        }
    }

    /**
     * Construct (but do <em>not</em> start) a {@link ServerSnapshotClient} pointed at the running
     * dedicated server, resolving host/port via the same port-file / property / env / default chain
     * as the shared menu client. Used by the per-combat connection mode: the
     * per-mission {@link ServerFedPuppetCombatPlugin} starts and owns one of these per combat and is
     * responsible for {@link ServerSnapshotClient#start()} and {@link ServerSnapshotClient#close()}.
     */
    static ServerSnapshotClient newServerClient() {
        String host = CcClientModPlugin.DEFAULT_HOST;
        int port = CcClientModPlugin.DEFAULT_PORT;
        String[] fromFile = ClientLaunchKnobs.readPortFile();
        if (fromFile != null) {
            host = fromFile[0];
            port = Integer.parseInt(fromFile[1]);
        } else {
            host = ClientLaunchKnobs.resolveHost();
            port = ClientLaunchKnobs.resolvePort();
        }
        return new ServerSnapshotClient(host, port);
    }
}
