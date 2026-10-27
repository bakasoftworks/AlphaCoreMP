package cc.starsector.client;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * The client's launch-knob resolvers: the {@code cc.*} system property &gt; {@code CC_*} env var
 * &gt; port-file chains read once at launch (pilot / predict / snapshot-server endpoints, the
 * predict connect budget + transport, the per-combat connect switch), plus the application-load
 * describe helpers that echo the delivered {@code cc.predict.*} table and the kit launch shape.
 *
 * <p>WHY a class of its own: {@link CcClientModPlugin} had grown to 2612 non-blank lines with no
 * tier, and its one real concern is the predict dial; these resolvers hold NO plugin state and
 * start NO threads — they were homed there for convenience only. Moved VERBATIM (bodies,
 * javadoc, every announce/log line); the plugin calls them qualified, and keeps the public
 * constants ({@link CcClientModPlugin#DEFAULT_HOST}, {@link CcClientModPlugin#DEFAULT_PORT}) they
 * read; the dial's {@link PredictDialLane#PREDICT_CONNECT_ATTEMPTS} is the retry-budget default.
 *
 * <p>Census law: the {@code cc.*} sysprop and {@code CC_*} env literals stay INLINE and
 * byte-identical at each call — a knob-census tool sweeps {@code client-mod/src} for
 * the literals (the posture-census hand tool, whose NONLIT-getenv allowance keyed on the
 * forwarding parameter name {@code env} in {@link #readPortFileNamed} / {@link #resolvePilotLong},
 * was retired — keep those parameter names: they document the env-twin seam).
 *
 * <p>Provenance: a planned carve out of the plugin.
 *
 *
 *
 *
 */
final class ClientLaunchKnobs {

    private ClientLaunchKnobs() {
    }

    /** The DELIVERED {@code cc.predict.*} property table
     *  (sorted; values redacted for credential-shaped keys — password/token/secret/fingerprint
     *  never reach a log). Fail-soft: enumeration trouble reports itself instead of breaking
     *  application load. */
    static String describeDeliveredPredictKnobs() {
        try {
            java.util.TreeMap<String, String> delivered = new java.util.TreeMap<String, String>();
            for (String key : System.getProperties().stringPropertyNames()) {
                if (!key.startsWith("cc.predict.")) {
                    continue;
                }
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                boolean redact = lower.contains("password") || lower.contains("token")
                        || lower.contains("secret") || lower.contains("fingerprint");
                delivered.put(key, redact ? "<set>" : System.getProperty(key));
            }
            if (delivered.isEmpty()) {
                return "delivered[none] (all cc.predict.* knobs at compiled defaults)";
            }
            StringBuilder sb = new StringBuilder("delivered[n=").append(delivered.size()).append(':');
            for (java.util.Map.Entry<String, String> e : delivered.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "delivered[UNREADABLE: " + t.getClass().getSimpleName() + "]";
        }
    }

    /**
     * The bundled-Java fallback must be detectable in LOGS,
     * not only in the joiner console: join-cc.bat sets {@code -Dcc.kit.launch=fallback-probe}
     * (jre-cc failed {@code java -version} before the join window) or {@code fallback-start}
     * (the {@code start javaw.exe} launch was refused / died within 5 s) when the install's own
     * {@code jre\} took over. This is the ONE sidelog row that says so (joiner-debrief bundles the
     * sidelog into the Desktop report); the Java version beside it is the cross-check (jre-cc =
     * Temurin 25, the install jre = Zulu 17). Unset = the bundled runtime ran, or a no-bundle kit.
     */
    static String describeKitLaunch() {
        String v = System.getProperty("cc.kit.launch");
        // The sysprop is launcher-authored text riding a log line — sanitize
        // like every other externally-sourced fragment (single-line printable, bounded).
        // "explorer" = join-cc.bat chose the Explorer-parent launch shape (the
        // sound self-check's rc 20, or CC_JOIN_LAUNCH=explorer) — the install's jre\ via starsector.exe.
        // "explorer-fallback" = that Explorer launch (explorer-launch.ps1) did not bring Java up, so the bat
        // started starsector.exe directly from its own window.
        // "unset" has a THIRD, unmarked cause — a kit folder
        // missing kit-jvm.args (a half-copied kit) launches on the install jre\ with no marker;
        // the java.home basename beside the version is the cross-check that tells them apart
        // (jre-cc = the bundled runtime, jre = the install's own). java.home is launcher-owned
        // text riding a log line: basename only, sanitized, bounded (no java.nio.file — the
        // jar-content gate bans it from the mod jar).
        return (v == null ? "unset (bundled jre-cc ran, a no-bundle kit, or a kit missing kit-jvm.args - see home)"
                : "explorer".equals(v) ? "EXPLORER-PARENT (join-cc.bat chose the Explorer launch shape)"
                : "explorer-fallback".equals(v) ? "EXPLORER-FALLBACK (the Explorer launch brought no Java up; join-cc.bat started starsector.exe directly)"
                : "FALLBACK " + FallbackAnnounce.sanitize(v, 32))
                + " | java " + System.getProperty("java.version")
                + " home=" + FallbackAnnounce.sanitize(javaHomeBasename(), 32);
    }

    /** The last path element of {@code java.home} ({@code jre-cc} / {@code jre} / …), or
     *  {@code ?} when unset; plain string ops so the mod jar stays java.nio.file-free. */
    static String javaHomeBasename() {
        String home = System.getProperty("java.home");
        if (home == null || home.isEmpty()) {
            return "?";
        }
        int end = home.length();
        while (end > 0 && (home.charAt(end - 1) == '\\' || home.charAt(end - 1) == '/')) {
            end--;
        }
        int cut = Math.max(home.lastIndexOf('\\', end - 1), home.lastIndexOf('/', end - 1));
        return end == 0 ? "?" : home.substring(cut + 1, end);
    }

    /** The predict-lane connect retry budget: {@code cc.predict.connectAttempts} / {@code
     *  CC_PREDICT_CONNECT_ATTEMPTS} when &gt; 0, else {@link PredictDialLane#PREDICT_CONNECT_ATTEMPTS}. */
    static int resolvePredictConnectAttempts() {
        long override = resolvePilotLong("cc.predict.connectAttempts", "CC_PREDICT_CONNECT_ATTEMPTS", 0L);
        return (override > 0L && override <= Integer.MAX_VALUE) ? (int) override : PredictDialLane.PREDICT_CONNECT_ATTEMPTS;
    }

    /** Transport select: {@code cc.predict.transport} / {@code CC_PREDICT_TRANSPORT}
     * = {@code udp}, unset = udp; {@code tcp} THROWS — the TCP lane is FULL-DELETED at source,
     * so an explicit tcp is a stale kit/config and must fail loud, never silently
     * pick a transport (the server-side {@code net.transport} mirror exactly — strict values,
     * no aliases; E3 ADJ-B flip). */
    static String resolvePredictTransport() {
        String raw = System.getProperty("cc.predict.transport");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_PREDICT_TRANSPORT");
        }
        if (raw == null || raw.isBlank()) {
            return "udp";
        }
        String v = raw.trim();
        if (v.equals("udp")) {
            return v;
        }
        throw new IllegalArgumentException("cc.predict.transport must be udp (tcp lane"
                + " FULL-DELETED — stale kit/config), got: " + raw);
    }

    /**
     * Mint the EPHEMERAL client-instance id for password mode when no
     * {@code cc.predict.clientId} arrived (env drills, hand launches). 32-hex like JoinGui's
     * persisted mint, from {@link java.security.SecureRandom}
     * (the class the in-game {@code DoorProbe} already uses). The id is a seat KEY, not a secret:
     * admission security pins on the match password; uniqueness only has to beat the other
     * joiners of one match — except on the host's own box, where a cid the host console ARMS as
     * admin (its command-file line) grants admin at HELLO for that serve life: the console hands
     * its windows theirs through the env twin and no log row prints one whole. One caller:
     * {@code PredictDialPlanner} at application load, once per process (never inside a combat frame).
     */
    static String mintEphemeralClientId() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        return String.format(java.util.Locale.ROOT, "%016x%016x", rng.nextLong(), rng.nextLong());
    }

    /** First non-blank of (property value, env value), trimmed, else the fallback — the knob
     * literals stay INLINE at the call sites so the knob-census tool's literal scan sees them. */
    static String firstNonBlank(String propValue, String envValue, String fallback) {
        if (propValue != null && !propValue.isBlank()) {
            return propValue.trim();
        }
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        return fallback;
    }

    /**
     * With {@code cc.observer.reel} set on an OBSERVER window, start (once,
     * idempotent) the in-process loopback door and dial IT — no port file, no live server; the
     * reel's {@code descriptor.id} must equal the loaded descriptor's match id (a wrong roster
     * renders garbage). Fail loud: a bad reel, a bind failure or a mismatch logs + announces and
     * yields {@code null} (LOCAL VANILLA, visible in the briefing) — never a silent live dial.
     * A pilot window ignores the knob with one announce.
     */
    static String[] resolveReelDoorEndpoint() {
        String reel = ObserverMode.reel();
        if (reel == null) {
            return null;
        }
        if (!ObserverMode.ON) {
            FallbackAnnounce.announceOnce("observer-reel-ignored",
                    "knob=cc.observer.reel set on a NON-observer window -> ignored (live dial)");
            return null;
        }
        try {
            cc.starsector.client.net.ReelLoopbackDoor.Options o =
                    new cc.starsector.client.net.ReelLoopbackDoor.Options();
            o.ccwrPath = reel;
            o.speed = ObserverMode.reelSpeed();
            o.matchFlags = ObserverMode.reelFlags();
            long[] drop = ObserverMode.parseReelDrop(ObserverMode.reelDrop()); // The tri-part parse now lives in ObserverMode.parseReelDrop (selftest seam); a malformed value still throws here -> the catch below degrades to LOCAL VANILLA
            if (drop != null) {
                o.dropShipId = drop[0];
                o.dropFromTick = drop[1];
                o.dropToTick = drop[2];
            }
            o.log = PilotWatchLog::info;
            cc.starsector.client.net.ReelLoopbackDoor door =
                    cc.starsector.client.net.ReelLoopbackDoor.startOrGet(o);
            cc.starsector.server.match.MatchDescriptor desc = MatchMissionBuilder.cachedDescriptor();
            String reelId = door.meta("descriptor.id");
            if (desc != null && reelId != null && !reelId.equals(desc.matchId())) {
                throw new IllegalStateException("reel descriptor.id=" + reelId + " != loaded descriptor "
                        + desc.matchId() + " (feed the reel's own descriptor file via cc.match.descriptor)");
            }
            return new String[] {"127.0.0.1", Integer.toString(door.port())};
        } catch (Exception e) {
            PilotWatchLog.error("observer reel: door start FAILED -> LOCAL VANILLA (no dial): " + e, e);
            FallbackAnnounce.announceOnce("observer-reel-failed", "cc.observer.reel door failed: "
                    + FallbackAnnounce.sanitize(String.valueOf(e.getMessage()), 96));
            return null;
        }
    }

    /**
     * A resolved predict endpoint plus WHERE it came from. {@code host} = a non-blank host text,
     * {@code port} = the decimal port text exactly as resolved (callers parse it; a value that
     * does not parse to 1..65535 is the caller's to refuse). {@code reelDoor} is true ONLY when
     * {@link #resolveReelDoorEndpoint} produced it (the in-process loopback reel replay door) —
     * never inferred from {@code 127.0.0.1}, which is also what a REAL server on this PC looks
     * like. WHY a tag: the dial plan must never hand the typed match password to the replay door
     * ({@link PredictDialPlan#credentialsFor}), and a click-time re-resolve whose tag differs from
     * the plan's must not dial ({@link PredictDialPlan#provenanceMoved}).
     *
     *
     */
    static final class PredictEndpoint {
        final String host;
        final String port;
        final boolean reelDoor;

        PredictEndpoint(String host, String port, boolean reelDoor) {
            this.host = host;
            this.port = port;
            this.reelDoor = reelDoor;
        }
    }

    /**
     * Resolve the predict endpoint: the observer reel door ({@link #resolveReelDoorEndpoint} — the
     * only branch that tags {@link PredictEndpoint#reelDoor}), then a port-file
     * ({@code cc.predict.portFile} / {@code CC_PREDICT_PORT_FILE} — the same {@code host=}/{@code port=}
     * format the pilot watch writes), then explicit {@code cc.predict.host}/{@code cc.predict.port}
     * ({@code CC_PREDICT_HOST}/{@code CC_PREDICT_PORT}), or {@code null} when nothing is configured.
     * Uses the allowlisted {@code FileReader} path.
     */
    static PredictEndpoint resolvePredictEndpoint() {
        String[] reelDoor = resolveReelDoorEndpoint(); // wq P18: the reel door beats every live endpoint
        if (reelDoor != null) {
            return new PredictEndpoint(reelDoor[0], reelDoor[1], true);
        }
        String[] fromFile = readPortFileNamed("cc.predict.portFile", "CC_PREDICT_PORT_FILE");
        if (fromFile != null) {
            return new PredictEndpoint(fromFile[0], fromFile[1], false);
        }
        String host = System.getProperty("cc.predict.host");
        if (host == null || host.isBlank()) {
            host = System.getenv("CC_PREDICT_HOST");
        }
        long port = resolvePilotLong("cc.predict.port", "CC_PREDICT_PORT", 0L);
        if (port <= 0L) {
            return null;
        }
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        return new PredictEndpoint(host.trim(), Long.toString(port), false);
    }

    /** (83) CL-7a: any {@code cc.predict.*} endpoint knob non-blank (prop or env) — the signal
     * this launch INTENDED a predict session, making a null endpoint resolve a confessable
     * degrade rather than an ordinary launch. */
    static boolean predictEndpointKnobPresent() {
        return firstNonBlank(System.getProperty("cc.predict.portFile"),
                        System.getenv("CC_PREDICT_PORT_FILE"), null) != null
                || firstNonBlank(System.getProperty("cc.predict.host"),
                        System.getenv("CC_PREDICT_HOST"), null) != null
                || firstNonBlank(System.getProperty("cc.predict.port"),
                        System.getenv("CC_PREDICT_PORT"), null) != null;
    }

    /**
     * Resolve the pilot {@link cc.starsector.server.runtime.PilotIntentSocketServer} endpoint for this
     * window: a port-file ({@code cc.pilot.portFile} / {@code CC_PILOT_PORT_FILE} — the
     * {@code host=}/{@code port=} file the scout watch-mode writes), then explicit
     * {@code cc.pilot.host}/{@code cc.pilot.port} (then {@code CC_PILOT_HOST}/{@code CC_PILOT_PORT}), or
     * {@code null} when nothing is configured. Mirrors the resolution the combat plugin used to do, moved
     * here so the socket opens at application load.
     */
    static String[] resolvePilotEndpoint() {
        String[] fromFile = readPilotPortFile();
        if (fromFile != null) {
            return fromFile;
        }
        String host = System.getProperty("cc.pilot.host");
        if (host == null || host.isBlank()) {
            host = System.getenv("CC_PILOT_HOST");
        }
        long port = resolvePilotLong("cc.pilot.port", "CC_PILOT_PORT", 0L);
        if (port <= 0L) {
            return null; // no explicit endpoint and no port-file -> unconfigured
        }
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        return new String[] {host.trim(), Long.toString(port)};
    }

    /**
     * Read {@code host}/{@code port} from a configured pilot port-file, or {@code null} when none is
     * configured / readable — delegates to {@link #readPortFileNamed} ((83) CL-7b fold: the three
     * portfile readers were byte-identical bar the knob names).
     */
    static String[] readPilotPortFile() {
        return readPortFileNamed("cc.pilot.portFile", "CC_PILOT_PORT_FILE");
    }

    /**
     * Read {@code host}/{@code port} from a port-file named by the given property ({@code prop}) then env
     * var ({@code env}), or {@code null} when none is configured / readable. The ONE portfile reader
     * ((83) CL-7b — {@link #readPilotPortFile()} / {@link #readPortFile()} delegate here). Starsector
     * 0.98a sandboxes mod code and blocks {@code java.nio.file.*}, so this uses the
     * allowlisted {@code FileReader}/{@code BufferedReader}. A file that is configured yet
     * missing/partial/non-numeric still returns {@code null} (callers fall through to explicit-port
     * resolution or reconnect-retry), but now confesses once per knob — set-but-unreadable used to be
     * indistinguishable from unconfigured at grading time.
     */
    static String[] readPortFileNamed(String prop, String env) {
        String raw = System.getProperty(prop);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(env);
        }
        if (raw == null || raw.isBlank()) {
            return null; // not configured — an ordinary launch, silent
        }
        String host = null;
        String port = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(raw.trim()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ("host".equals(key)) {
                    host = value;
                } else if ("port".equals(key)) {
                    port = value;
                }
            }
        } catch (IOException e) {
            FallbackAnnounce.announceOnce("portfile-bad#" + prop, "set but unreadable: "
                    + e.getClass().getSimpleName() + " -> resolver falls through");
            return null;
        }
        if (host == null || host.isBlank() || port == null || port.isBlank()) {
            FallbackAnnounce.announceOnce("portfile-bad#" + prop,
                    "partial (host/port row missing) -> resolver falls through");
            return null;
        }
        try {
            Integer.parseInt(port);
        } catch (NumberFormatException e) {
            FallbackAnnounce.announceOnce("portfile-bad#" + prop,
                    "port non-numeric -> resolver falls through");
            return null;
        }
        return new String[] {host, port};
    }

    /** Resolve a {@code long} from a system property, then an env var, then a fallback — via the
     * {@link KnobParse} idiom ((83) CL-7c): a malformed non-blank value announces once instead of
     * silently pinning the fallback (a {@code cc.predict.shipId} typo used to pin seat 1). */
    static long resolvePilotLong(String prop, String env, long fallback) {
        return KnobParse.longOr(prop, env, fallback, "cfg-long-unparseable#" + prop);
    }

    /**
     * Whether the client connects <em>per combat</em> instead of once at the menu,
     * from {@code cc.client.connectPerCombat} (then {@code CC_CLIENT_CONNECT_PER_COMBAT}); default
     * {@code false} keeps the proven shared-menu-client two-window {@code cc_puppet_proof} path
     * exactly as before. Any of {@code 1/true/yes/on} (case-insensitive) enables it. The
     * despawn-watch demo launcher sets it on so the fresh-binding-per-match despawn aligns with
     * combat start rather than client connect.
     */
    static boolean connectPerCombat() {
        String raw = System.getProperty("cc.client.connectPerCombat");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_CLIENT_CONNECT_PER_COMBAT");
        }
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    /**
     * Resolve {@code host}/{@code port} from a configured server port-file, or {@code null} when
     * none is configured / readable — delegates to {@link #readPortFileNamed} ((83) CL-7b fold).
     * Still tolerates a partially-written file by returning {@code null} (the reader then retries
     * via reconnect; the one-shot announce fires at most once per boot regardless of retries).
     */
    static String[] readPortFile() {
        return readPortFileNamed("cc.server.portFile", "CC_SERVER_PORT_FILE");
    }

    static String resolveHost() {
        String host = System.getProperty("cc.server.host");
        if (host == null || host.isBlank()) {
            host = System.getenv("CC_SERVER_HOST");
        }
        return (host == null || host.isBlank()) ? CcClientModPlugin.DEFAULT_HOST : host.trim();
    }

    static int resolvePort() {
        String raw = System.getProperty("cc.server.port");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_SERVER_PORT");
        }
        if (raw == null || raw.isBlank()) {
            return CcClientModPlugin.DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return CcClientModPlugin.DEFAULT_PORT;
        }
    }
}
