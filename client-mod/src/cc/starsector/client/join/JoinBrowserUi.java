package cc.starsector.client.join;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sound.sampled.Mixer;

import cc.starsector.client.net.DoorProbe;
import cc.starsector.client.voice.VoiceClientSettings;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDescriptor;

/**
 * The join window's BROWSER mode (GUI v2: the "Fallback + switch" design,
 * with its own responder and form): the responder ({@link JoinHost} + {@link JoinRoutes}) is bound, the
 * endpoint file written, the browser opened on {@code http://127.0.0.1:<port>/<token>/}, and this JVM then lives
 * as the session the SPA talks to — exactly the process contract {@code join-cc.bat} already has with the Swing
 * window (plan file = Launch, {@code .cancel} = abandoned, the JVM stays as the launch face). Selected by
 * {@code -Dcc.join.ui=browser} ({@link #selected}); the bat forwards {@code CC_JOIN_UI}.
 *
 * <p>Fallback, auto-selected (never a toggle the stranger must find): a loopback bind failure, a
 * {@code Desktop.browse()} failure, or a browser that never loads the page ({@link #FIRST_ALIVE_GRACE_MS} with no
 * heartbeat) ⇒ {@link Outcome#fallbackNote} is set and the caller opens the retained Swing form with that one-line
 * note. The admin auto-auth lane ({@code CC_JOIN_AUTOLAUNCH}) never selects this mode.
 *
 * <p>Single instance: before binding, a live earlier window (its endpoint file's port answers
 * {@code api/ping} with the file's token) is re-opened in the browser and THIS window cancels its own plan — no
 * lock file, no stale-lock class (a dead window's file simply does not answer and is overwritten).
 *
 * <p>Lifetime law (000137 died on the old tab law):
 * this JVM's life is the LAUNCHER's, never the tab's. {@code join-cc.bat} refreshes {@code <plan>.alive}
 * ({@link JoinCore#launcherAliveMarker}) every second while it polls for the plan; once that file has been seen,
 * {@link #LAUNCHER_STALE_MS} without a change (or the file gone) means the console was closed ⇒ this JVM exits, no
 * {@code .cancel} (nobody is polling). A hidden, throttled, frozen or CLOSED tab is NOTHING: the page's heartbeat
 * ({@code api/alive}, {@link #ALIVE_PERIOD_MS}) only tells the SPA it is connected, and its FIRST arrival cancels
 * the never-opened fallback ({@link #FIRST_ALIVE_GRACE_MS} ⇒ the Swing window). Re-open = the single-instance law
 * ({@link #run}: a second bat run or the Ops GUI button re-opens the live endpoint's tab; after a reboot the file
 * is dead ⇒ a fresh window). Never after Launch — the launch face follows the COMPANION law ({@link #companionLoop}:
 * the Swing face's poller, {@code JoinGui.showLaunchFace}, with the same {@code JoinCore} verdict/lines) and the
 * JVM leaves when it says {@code exit}. Lifecycle lines also land in {@link #LOG_FILE} ({@link #log}; javaw drops
 * stderr).
 *
 * <p>The form's three verbs (step 3), one law each: {@link #paste} = the connect code ({@link JoinLaunchPlan#parseBrick});
 * {@link #probe} = the ship-list fetcher ({@code ShipListFetcher}: the same readiness rule, cached fingerprint,
 * generation guard, the served default remembered for the plan) that also ADOPTS the server's match document
 * (staged as raw text, re-checked at Launch); {@link #launch} = {@link JoinLaunchGates} check + commit.
 */
final class JoinBrowserUi implements JoinRoutes.Session {
    /**
     * The mode when {@code -Dcc.join.ui} is unset: {@code browser} — the SHIPPED joiner, flipped with
     * step 4 once the form, the launch face and the voice bridge were all in; {@code swing}
     * ({@code CC_JOIN_UI=swing} through the bat, or the admin auto-auth env) is the retained native window.
     */
    static final String DEFAULT_UI = "browser";
    /** The form's "Open editor" answer before the game answers by nonce (also the SPA's hint text). */
    static final String EDITOR_LAUNCH_FIRST = "launch first - the editor opens from the running game";
    static final long ALIVE_PERIOD_MS = 2000;
    /** The launcher's {@code <plan>.alive} unchanged for this long (after first sight) ⇒ the console is gone ⇒ exit;
     *  {@code -Dcc.join.launcherStaleMs} overrides (smoke). Counted in {@link #watch} polls, so a suspended box does
     *  not count its sleep against the bat (which ticks again within a second of waking). */
    static final long LAUNCHER_STALE_MS = 60000;
    static final long WATCH_POLL_MS = 500;
    /** Lifecycle lines (never a token, never a secret) beside {@link JoinSettings#defaultPath} — javaw has no stderr.
     *  Never migrated from the old roaming folder: the old log holds no secret. */
    static final Path LOG_FILE = JoinSettings.defaultPath().resolveSibling("join-window.log");
    /** A browser that has not loaded the page within this ⇒ the Swing fallback; {@code -Dcc.join.aliveGraceMs} overrides (smoke). */
    static final long FIRST_ALIVE_GRACE_MS = 45000;
    static final int PING_TIMEOUT_MS = 2000;

    static final String STATE_FORM = "form";
    static final String STATE_LAUNCHED = "launched";
    static final String STATE_CANCELLED = "cancelled";

    /** The api/launch refusal when the plan is already the launcher's (a fixed gate word the SPA keys on). */
    static final String GATE_STATE = "state";
    static final String MSG_ALREADY_LAUNCHED = "The launch plan was already handed to the launcher - this page"
            + " follows the game now.";
    /** The face's line once the game stopped answering after having answered (the Swing face's wording). */
    static final String LINE_NOT_ANSWERING = "Starsector: not answering (this window closes if it stays quiet)...";

    /** Either an exit code for the JVM, or a note for the Swing fallback (exactly one is non-null). */
    static final class Outcome {
        final Integer exitCode;
        final String fallbackNote;

        private Outcome(Integer exitCode, String fallbackNote) {
            this.exitCode = exitCode;
            this.fallbackNote = fallbackNote;
        }

        static Outcome exit(int code) {
            return new Outcome(code, null);
        }

        static Outcome fallback(String note) {
            return new Outcome(null, note);
        }
    }

    /** stderr + {@link #LOG_FILE} (appended; restarted past 256 KB) — the join window's only trace under javaw. */
    static void log(String line) {
        System.err.println(line);
        try {
            Files.createDirectories(LOG_FILE.getParent());
            if (Files.exists(LOG_FILE) && Files.size(LOG_FILE) > 256 * 1024) {
                Files.delete(LOG_FILE);
            }
            Files.write(LOG_FILE, (java.time.LocalDateTime.now().withNano(0) + " " + line + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException unwritable) {
            // stderr already carries the line
        }
    }

    /** {@code -Dcc.join.ui=browser} selects this mode; the admin auto-auth lane always stays on the Swing window. */
    static boolean selected() {
        return "browser".equalsIgnoreCase(System.getProperty("cc.join.ui", DEFAULT_UI))
                && !"1".equals(System.getenv("CC_JOIN_AUTOLAUNCH"));
    }

    private final Path modDir;
    private final Path planFile;
    private final String nonce;
    private final Path settingsPath;
    private final JoinSettings saved;
    private final long startNanos = System.nanoTime();
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<String> state = new AtomicReference<>(STATE_FORM);
    private final AtomicReference<Outcome> outcome = new AtomicReference<>();
    private volatile boolean firstAliveSeen;
    private volatile JoinHost host;

    // ---- the form's verbs (step 3)
    private final AtomicInteger probeGen = new AtomicInteger();
    /** The probe print, KEYED by the match id it was computed for ({@link #printFor}): the staged id, else null. */
    private final AtomicReference<KeyedPrint> fingerprint = new AtomicReference<>();
    /** The last served server-default id (null = none) — the plan carries it for an empty pick. */
    private volatile String fetchedDefault;
    /** The last SERVED probe's default + mod-set diff, keyed to the pair it asked
     *  — the Launch gate + the staging read them through it; NONE = no served probe yet ⇒ the root holds
     *  the kit alone. Same generation guard as the default above (which stays for api/state's label only). */
    private volatile JoinLaunchGates.Probed probed = JoinLaunchGates.Probed.NONE;
    /** The server's match document a probe adopted (its raw text, re-checked at Launch, which writes it as
     *  {@code match.txt}); a brick or an emptied paste box clears it, and so does a probe of another pair. */
    private volatile String stagedCredential;
    private volatile String stagedSummaryJson;
    /** The staged document's {@code match.id} — set and cleared
     *  beside {@link #stagedCredential}; null = nothing staged ⇒ the probe presents the cached file's own id. */
    private volatile String stagedMatchId;
    /** The staged document seats a pilot on both sides (set beside
     *  {@link #stagedSummaryJson}; read only while a stage of the probed pair is held). */
    private volatile boolean stagedTwoSided;
    /** The pair whose probe adopted the staged document (host trimmed); "" / -1 = nothing staged. */
    private volatile String stagedHost = "";
    private volatile int stagedPort = -1;
    /** Every api/probe's outcome past its generation guard, keyed to
     *  its pair — the launch gate's input when nothing is staged ({@link JoinLaunchGates#check}). */
    private volatile JoinLaunchGates.ProbeOutcome lastProbe = JoinLaunchGates.ProbeOutcome.NONE;
    private final Object launchLock = new Object();

    // ---- the launch face (after Launch): the companion law, polled by companionLoop
    private volatile String launchedJson;
    private volatile String companionVerdict;
    private volatile String companionLine;
    private volatile String editorUrl;
    private volatile boolean companionSeenLive;
    /** Iteration 9 (c): the once-per-launch editor auto-open latch (set on the first live ping). */
    private volatile boolean editorAutoOpened;

    // ---- the voice bridge: JoinVoiceCore in THIS JVM, the SPA's Options row over it
    private final Path voiceFile;
    private final JoinVoiceCore.Tester tester = new JoinVoiceCore.Tester();
    /** The list api/voice/devices last enumerated — the index law's referent for Test and Launch. */
    private volatile List<Mixer.Info> mixers = Collections.emptyList();
    private volatile int voiceLevel;
    private volatile String voiceLine = "";
    private final JoinVoiceCore.Listener voiceListener = new JoinVoiceCore.Listener() {
        @Override
        public void level(long runId, int percent) {
            if (tester.isCurrent(runId)) {
                voiceLevel = percent;
            }
        }

        @Override
        public void status(long runId, String line) {
            if (tester.isCurrent(runId)) {
                voiceLine = line;
            }
        }

        @Override
        public void failed(long runId, String line) {
            if (tester.isCurrent(runId)) {
                voiceLine = line;
                voiceLevel = 0;
            }
        }
    };

    private JoinBrowserUi(Path modDir, Path planFile, String nonce, Path settingsPath, JoinSettings saved) {
        this.modDir = modDir;
        this.planFile = planFile;
        this.nonce = nonce;
        this.settingsPath = settingsPath;
        this.saved = saved;
        this.voiceFile = JoinVoiceSettings.settingsFile(JoinVoiceSettings.installRoot(modDir));
    }

    /**
     * The door call every window probe makes ({@link #probe}) — the
     * probe SEAM of row {@code paste-re-probes} ({@code JoinGuiProbeSelftest}), which swaps a fake door in and
     * restores this default in {@code finally}. Production never writes it; the default is today's call
     * ({@link JoinProbe#fetch} = {@link DoorProbe#fetch13}, its five args unchanged).
     */
    interface DoorCall {
        DoorProbe.Result fetch(String host, int port, String password, String clientId, String fingerprint);
    }

    static volatile DoorCall doorCall = JoinProbe::fetch;

    /** One cached print + the match id it was computed for (null =
     *  the kit file's own id) — a staged paste changes the key, so the next probe rebuilds the print. */
    private static final class KeyedPrint {
        final String matchId;
        final JoinFingerprint.Computed computed;

        KeyedPrint(String matchId, JoinFingerprint.Computed computed) {
            this.matchId = matchId;
            this.computed = computed;
        }
    }

    /**
     * A window the paste-re-probes row drives with no browser, no
     * host socket and no real door (the {@link #doorCall} seam): plan / settings paths sit inside the row's temp
     * {@code modDir} and are never written by paste or probe. Test-only; {@link #run} stays the one real entry.
     */
    static JoinBrowserUi forSelftest(Path modDir, JoinSettings saved) {
        return new JoinBrowserUi(modDir, modDir.resolve("selftest-plan.txt"), "selftest",
                modDir.resolve("selftest-settings.properties"), saved);
    }

    /** The last served probe's launch state (the row reads it; {@link JoinLaunchGates.Probed#NONE} = none). */
    JoinLaunchGates.Probed probed() {
        return probed;
    }

    /** The last probe's outcome as the launch gate reads it (a row reads it; NONE = no probe yet). */
    JoinLaunchGates.ProbeOutcome lastProbe() {
        return lastProbe;
    }

    /** Runs the browser session to its end; blocks like {@code JoinGui.runGui}. */
    static Outcome run(Path modDir, Path planFile, String nonce) {
        Path endpointFile = JoinHost.endpointFile(modDir);
        if (endpointFile == null) {
            log("join-browser: LOCALAPPDATA unset or not absolute - no endpoint file, single-instance check off");
        }
        String live = liveWindowUrl(endpointFile);
        if (live != null) {
            log("join-browser: another join window is live on this box; re-opening it");
            openBrowser(live);
            JoinCore.writeCancelMarker(planFile);
            return Outcome.exit(2);
        }
        Path settingsPath = JoinSettings.pathForRun();
        JoinSettings saved = JoinSettings.load(settingsPath);
        log("join-browser: start (browser lane; log " + LOG_FILE.getFileName() + ")");
        JoinBrowserUi ui = new JoinBrowserUi(modDir, planFile, nonce, settingsPath, saved);
        return ui.serve(endpointFile);
    }

    private Outcome serve(Path endpointFile) {
        JoinHost h = new JoinHost(new JoinRoutes(this), endpointFile, nonce);
        try {
            h.start();
        } catch (IOException | RuntimeException bindFailed) {
            log("join-browser: loopback bind failed (" + bindFailed.getClass().getSimpleName()
                    + ") - native window");
            return Outcome.fallback("browser unavailable - native window (loopback bind failed)");
        }
        host = h;
        String url = h.baseUrl();
        if (!openBrowser(url)) {
            h.close();
            return Outcome.fallback("browser unavailable - native window (could not open a browser)");
        }
        Thread watchdog = new Thread(this::watch, "cc-join-alive");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            done.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // A Voice Test still running when the tab is abandoned would hold the
        // mic line open until process exit — stop it with the host (idempotent when idle).
        tester.stop();
        h.close();
        Outcome o = outcome.get();
        return o == null ? Outcome.exit(2) : o;
    }

    /**
     * {@code -Dcc.join.browse=off} (the game-free smoke) prints the URL instead of opening a browser and counts as
     * opened; {@code =fail} (the smoke's forced fallback row) counts as a browse failure without touching a
     * browser; otherwise {@code Desktop.browse} — false on any failure (headless JVM, no default browser, …).
     */
    private static boolean openBrowser(String url) {
        String mode = System.getProperty("cc.join.browse", "on");
        if ("off".equalsIgnoreCase(mode)) {
            System.out.println("join-browser: " + url);
            return true;
        }
        if ("fail".equalsIgnoreCase(mode)) {
            log("join-browser: -Dcc.join.browse=fail - could not open a browser (forced) - native window");
            return false;
        }
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                log("join-browser: Desktop.browse unsupported on this JVM - native window");
                return false;
            }
            Desktop.getDesktop().browse(new URI(url));
            return true;
        } catch (Exception | Error browseFailed) {
            log("join-browser: Desktop.browse failed (" + browseFailed.getClass().getSimpleName()
                    + ") - native window");
            return false;
        }
    }

    /** The earlier window's base URL when its endpoint file's port answers {@code api/ping} with the file's token;
     *  null with no file ({@code endpointFile} null = no {@code LOCALAPPDATA}, {@link JoinHost#endpointFile}). */
    static String liveWindowUrl(Path endpointFile) {
        String text;
        try {
            if (endpointFile == null || !Files.isRegularFile(endpointFile) || Files.size(endpointFile) > 256) {
                return null;
            }
            text = new String(Files.readAllBytes(endpointFile), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        String base = JoinHost.parseEndpoint(text);
        if (base == null) {
            return null;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "api/ping").openConnection();
            c.setConnectTimeout(PING_TIMEOUT_MS);
            c.setReadTimeout(PING_TIMEOUT_MS);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            boolean ours = false;
            if (code == 200) {
                byte[] buf = new byte[1024];
                int n = 0;
                try (InputStream in = c.getInputStream()) {
                    int r;
                    while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) {
                        n += r;
                    }
                }
                ours = new String(buf, 0, n, StandardCharsets.UTF_8).contains("\"app\":\"join\"");
            }
            c.disconnect();
            return ours ? base : null;
        } catch (IOException | RuntimeException down) {
            return null;
        }
    }

    private static long msProperty(String name, long dflt, long floor) {
        String override = System.getProperty(name);
        if (override != null) {
            try {
                return Math.max(floor, Long.parseLong(override.trim()));
            } catch (NumberFormatException ignored) {
                // the default stands
            }
        }
        return dflt;
    }

    /** One look at the launcher's {@code <plan>.alive}: its mtime + bytes, or null when it is not there. */
    private static String launcherTick(Path marker) {
        try {
            return Files.getLastModifiedTime(marker).toMillis() + "|" + new String(Files.readAllBytes(marker),
                    StandardCharsets.ISO_8859_1).trim();
        } catch (IOException | RuntimeException gone) {
            return null;
        }
    }

    /**
     * The lifetime watchdog: (1) the never-opened fallback — no first heartbeat within the grace ⇒ the Swing
     * window; (2) the launcher law — the bat's {@code .alive} tick, once seen, unchanged or unreadable for {@link
     * #LAUNCHER_STALE_MS} ⇒ exit. Nothing here reads the tab: a page that stops heartbeating is a page
     * that stopped heartbeating, and the join stays open for it.
     */
    private void watch() {
        long graceMs = msProperty("cc.join.aliveGraceMs", FIRST_ALIVE_GRACE_MS, 1000L);
        long stalePolls = Math.max(1L, msProperty("cc.join.launcherStaleMs", LAUNCHER_STALE_MS, 2000L) / WATCH_POLL_MS);
        Path marker = JoinCore.launcherAliveMarker(planFile);
        String lastTick = null;
        long unchangedPolls = 0;
        while (done.getCount() > 0) {
            try {
                Thread.sleep(WATCH_POLL_MS);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (!STATE_FORM.equals(state.get())) {
                continue; // launched: the face lives on the companion verdict; the bat has stopped ticking
            }
            String tick = launcherTick(marker);
            if (tick != null && !tick.equals(lastTick)) {
                if (lastTick == null) {
                    log("join-browser: launcher tick seen (" + marker.getFileName() + ") - this window lives while the"
                            + " launcher console does; the tab may be hidden, closed or re-opened freely");
                }
                lastTick = tick;
                unchangedPolls = 0;
            } else if (lastTick != null) {
                // a null look counts like an unchanged one: a read racing the bat's own rewrite (or a transient
                // sharing violation) must never read as "gone" — only a full stale span ends the window
                unchangedPolls++;
                if (unchangedPolls >= stalePolls) {
                    log("join-browser: launcher " + (tick == null ? "marker gone" : "tick stale") + " for "
                            + unchangedPolls * WATCH_POLL_MS + " ms - the console was closed, leaving");
                    finish(Outcome.exit(2));
                    return;
                }
            }
            if (!firstAliveSeen && (System.nanoTime() - startNanos) / 1000000L > graceMs) {
                log("join-browser: no page load within " + graceMs + " ms - native window");
                finish(Outcome.fallback("browser unavailable - native window (the page never opened)"));
                return;
            }
        }
    }

    private void finish(Outcome o) {
        if (outcome.compareAndSet(null, o)) {
            done.countDown();
        }
    }

    // ------------------------------------------------------------ JoinRoutes.Session

    @Override
    public String state() {
        return state.get();
    }

    @Override
    public long uptimeMs() {
        return (System.nanoTime() - startNanos) / 1000000L;
    }

    @Override
    public void alive() {
        if (!firstAliveSeen) {
            log("join-browser: first heartbeat - the page is up in the browser");
        }
        firstAliveSeen = true;
    }

    @Override
    public boolean inForm() {
        return STATE_FORM.equals(state.get());
    }

    @Override
    public void cancel() {
        if (state.compareAndSet(STATE_FORM, STATE_CANCELLED)) {
            JoinCore.writeCancelMarker(planFile);
            finish(Outcome.exit(2));
        }
    }

    /**
     * The {@code api/state} body: the prefill the Swing form shows (saved host/port/name, the remembered pick
     * behind the current build, the local customs + their notes, the kit-credential PRESENCE, the logs tick) —
     * never the password ({@code hasPassword} only = one is remembered for the SAVED host/port this body carries),
     * never a secret; after Launch the launch face's texts + the
     * companion verdict/line/editor URL.
     */
    @Override
    public String stateJson() {
        Path installRoot = JoinVoiceSettings.installRoot(modDir);
        String currentBuild = CurrentBuildFile.read(installRoot);
        JoinJson j = new JoinJson()
                .put("ok", true)
                .put("ui", "browser")
                .put("state", state.get())
                .put("host", saved.host)
                .put("port", saved.port)
                .put("name", saved.name)
                .put("hasPassword", !saved.rememberedPasswordFor(saved.host, saved.port).isEmpty())
                .put("ship", saved.ship)
                .put("currentBuild", currentBuild)
                // the Swing form's initial pick: the current build ahead of the remembered pick (flow)
                .put("initialShip", currentBuild.isEmpty() ? saved.ship : currentBuild)
                .put("logs", saved.logs)
                .put("kitCredential", JoinCore.hasKitCredential(modDir))
                .put("serverDefaultLabel", JoinProbe.SERVER_DEFAULT_LABEL)
                .put("notReadyLine", JoinProbe.NOT_READY_LINE)
                .put("fetchedDefault", fetchedDefault)
                .putRaw("staged", stagedSummaryJson)
                .put("nonce", nonce)
                .put("alivePeriodMs", ALIVE_PERIOD_MS)
                .put("uptimeMs", uptimeMs());
        // the Options row's prefill = the shared voice file's mic keys (the Swing panel's prefill); the mixer LIST
        // is api/voice/devices (enumeration is slow-ish and the face never needs it)
        VoiceClientSettings v = JoinVoiceSettings.read(voiceFile);
        j.putRaw("voice", new JoinJson().put("device", v.micDevice).put("on", v.micOn)
                .put("gain", Math.round(v.micGain * 100f)).put("quality", v.complexity).toString());
        if (inForm()) {
            // the Swing form scans + publishes on open and on every Refresh (row (e)); the face never needs it
            JoinCore.LocalCustoms local = JoinCore.localCustomReport(installRoot);
            JoinCore.publishVariantIndex(installRoot, local);
            j.putStrings("localCustoms", local.ids).putStrings("customsNotes", local.notes);
        }
        j.putRaw("launched", launchedJson);
        j.putRaw("companion", companionJson());
        j.putRaw("face", new JoinJson()
                .put("purpose", JoinCore.launchFacePurpose(false))
                .put("live", JoinCore.LAUNCH_FACE_LIVE)
                .put("hint", JoinCore.LAUNCH_FACE_HINT)
                .toString());
        return j.toString();
    }

    private String companionJson() {
        String verdict = companionVerdict;
        if (verdict == null) {
            return null;
        }
        return new JoinJson()
                .put("verdict", verdict)
                .put("line", companionLine)
                .put("editor", editorUrl)
                .put("seenLive", companionSeenLive)
                .put("autoOpened", editorAutoOpened)
                .toString();
    }

    /** api/paste: the connect code — a brick fills three fields; an empty box clears the stage; anything
     *  else is refused as not a connect code, unparsed (the match itself arrives in the server's probe reply,
     *  {@link #probe}, never as a paste). The brick's password goes back into the masked field the player just
     *  filled — it is never rendered as text and never leaves this loopback.
     *
     *  <p>An empty box that CLEARS a staged document re-probes with the
     *  committed pair on the cached file's own id; the page sends host / port / password ONLY while its password is
     *  committed (the probe-commit law, app.js), and a null {@code host} probes nothing. A brick never probes.
     *
     *  <p>The credential half is gone — a non-brick text is one fixed
     *  line, never parsed, never staged, never echoed. */
    @Override
    public String paste(String text, String host, String port, String password) {
        String t = text == null ? "" : text;
        if (t.isBlank()) {
            boolean hadStage = stagedMatchId != null;
            clearStage();
            JoinJson empty = new JoinJson().put("ok", true).put("kind", "empty");
            if (hadStage && host != null) {
                empty.putRaw("probe", probe(host, port, password));
            }
            return empty.toString();
        }
        String[] brick = JoinLaunchPlan.parseBrick(t);
        if (brick != null) {
            clearStage();
            return new JoinJson().put("ok", true).put("kind", "brick")
                    .put("host", brick[0]).put("port", brick[1]).put("password", brick[2]).toString();
        }
        return JoinJson.error(MSG_NOT_A_CONNECT_CODE);
    }

    /** api/paste's one refusal: the box takes the connect code only. */
    static final String MSG_NOT_A_CONNECT_CODE = "Not a connect code - paste the address:port:password line the"
            + " host sent";

    /** A brick, an emptied box, a refused or missing document, or a probe of another pair empties the stage (the
     *  print falls back to the cached file's id). {@link #lastProbe} is NOT rewritten here: its
     *  {@code cacheCurrent} tells the launch gate whether it still holds without the stage. */
    private void clearStage() {
        stagedCredential = null;
        stagedMatchId = null;
        stagedSummaryJson = null;
        stagedHost = "";
        stagedPort = -1;
    }

    /** A lawful served document becomes the stage of the pair whose probe
     *  carried it; {@code staged} = the summary the page shows (match id + seat count). */
    private void stage(String text, MatchDescriptor d, String host, int port) {
        stagedSummaryJson = new JoinJson().put("matchId", d.matchId()).put("seats", d.seats().size()).toString();
        stagedTwoSided = MatchDescriptor.seatsPilotOnBothSides(d);
        stagedCredential = text;
        stagedMatchId = d.matchId();
        stagedHost = host;
        stagedPort = port;
    }

    /** The probe's status line in this window's words: the door's content-print refusal reads {@link JoinLaunchGates#MSG_KIT_DIFFERS} (the folder is not
     *  the host's kit); every other outcome is the shared {@link DoorProbe.Result#statusLine}. */
    static String windowLine(DoorProbe.Result result) {
        return "content_fingerprint_mismatch".equals(result.rejectReason)
                ? "Ship list: " + JoinLaunchGates.MSG_KIT_DIFFERS : result.statusLine();
    }

    /**
     * The probe print for {@code matchId} (the staged id; null =
     * the kit file's) — cached per id, rebuilt when the stage changes (it used to be computed ONCE per process from
     * the on-disk {@code match.txt}, so a pasted credential never reached the door). Blocking file reads.
     */
    private JoinFingerprint.Computed printFor(String matchId) {
        KeyedPrint cached = fingerprint.get();
        if (cached != null && Objects.equals(cached.matchId, matchId)) {
            return cached.computed;
        }
        JoinFingerprint.Computed print = JoinFingerprint.selfCompute(modDir, matchId);
        fingerprint.set(new KeyedPrint(matchId, print));
        return print;
    }

    /** api/probe: the Swing fetcher's law — not ready until address, port and a lawful password are
     *  all present (an empty field = the password remembered for THIS host:port, {@link #resolvePassword}); one
     *  blocking fetch on this request's own thread (the accept thread never waits), generation-guarded so a
     *  superseded result reads {@code stale}; the served default is remembered for the plan; the local
     *  customs re-scanned + the index published on a served list, exactly the Swing Refresh.
     *
     *  <p>The id sent is THIS host's ({@link JoinSettings#hostCid}, the
     *  master beside the settings file); a served reply carrying the match document is ADOPTED — the shared
     *  adopt ({@link JoinLaunchGates#adopt}: the window's receive law), staged, then ONE confirming re-probe presenting the
     *  new id, whose listing replaces the first (its own document, if any, waits for the next Refresh). Every
     *  outcome past the generation guard lands in {@link #lastProbe} for the launch gate; the reply adds
     *  {@code docLine} (fetched / missing / refused, or empty), {@code docOk} and {@code staged}. */
    @Override
    public String probe(String host, String port, String password) {
        String h = host == null ? "" : host.trim();
        String p = port == null ? "" : port.trim();
        String pw = resolvePassword(password, h, p);
        if (JoinLaunchPlan.validate(h, p) != null || pw.isEmpty() || JoinLaunchPlan.validatePassword(pw) != null) {
            return new JoinJson().put("ok", true).put("status", "not_ready").put("line", JoinProbe.NOT_READY_LINE)
                    .toString();
        }
        int gen = probeGen.incrementAndGet();
        int portNo = Integer.parseInt(p);
        JoinJson j = new JoinJson().put("ok", true);
        if (stagedMatchId != null && !JoinLaunchGates.samePair(stagedHost, stagedPort, h, p)) {
            clearStage();   // another server's document is never presented here, nor launched there
        }
        String cid;
        try {
            cid = JoinSettings.hostCid(settingsPath.resolveSibling(JoinSettings.CID_MASTER_FILE), h, portNo, true);
        } catch (IOException | RuntimeException ex) {
            String line = "could not mint this install's seat identity (" + ex.getClass().getSimpleName() + ")";
            log("join-browser: " + line);
            if (gen != probeGen.get()) {
                return new JoinJson().put("ok", true).put("status", "stale").toString();
            }
            lastProbe = new JoinLaunchGates.ProbeOutcome(JoinLaunchGates.ProbeOutcome.Kind.NO_REPLY, h, portNo, line,
                    false, "", false);
            return j.put("status", "error").put("line", line).toString();
        }
        JoinFingerprint.Computed print = printFor(stagedMatchId);
        String presented = print.print == null || print.print.isEmpty() ? null
                : ContentFingerprints.componentOrNull(print.print, "match");
        boolean presentedCachedId = stagedMatchId == null && presented != null
                && !ContentFingerprints.MATCH_NONE.equals(presented);
        DoorProbe.Result result = doorCall.fetch(h, portNo, pw, cid, print.print);
        if (gen != probeGen.get()) {
            return new JoinJson().put("ok", true).put("status", "stale").toString();
        }
        if (!result.served()) {
            String line = windowLine(result);
            lastProbe = new JoinLaunchGates.ProbeOutcome(result.rejectReason != null
                    ? JoinLaunchGates.ProbeOutcome.Kind.REJECTED : JoinLaunchGates.ProbeOutcome.Kind.NO_REPLY,
                    h, portNo, line, false, "", false);
            return j.put("status", result.rejectReason != null ? "rejected" : "error").put("line", line).toString();
        }
        // the adopt: one per api/probe (the shared JoinLaunchGates.adopt); a refusal names OUR reason + field, never
        // the document's bytes
        boolean sawDocument = result.sawDocument;
        JoinLaunchGates.Adopted adopted = JoinLaunchGates.adopt(result);
        String docLine = adopted.line;
        if (adopted.kind == JoinLaunchGates.Adopted.Kind.FETCHED) {
            stage(adopted.text, adopted.descriptor, h, portNo);
            print = printFor(stagedMatchId);
            DoorProbe.Result again = doorCall.fetch(h, portNo, pw, cid, print.print);
            if (gen != probeGen.get()) {
                return new JoinJson().put("ok", true).put("status", "stale").toString();
            }
            if (again.served()) {
                result = again;   // a confirming re-probe that went unanswered keeps the first listing
            }
        } else if (adopted.kind != JoinLaunchGates.Adopted.Kind.NONE) {
            // refused, or the server's match moved and did not arrive whole: a held older one is not it
            clearStage();
            if (adopted.refusal != null) {
                log("join-browser: the served match document was refused (" + adopted.refusal + ")");
            }
        }
        // a superseded probe of another pair may have staged ITS document after this probe's start: only this
        // pair's stage counts toward this pair's launch state
        boolean stagedHere = stagedCredential != null && JoinLaunchGates.samePair(stagedHost, stagedPort, h, p);
        // The outcome records WHICH case made docOk true — a stage can be cleared later without
        // re-recording it, and the launch gate honours only cacheCurrent or a stage the Form still carries
        boolean cacheCurrent = !sawDocument && presentedCachedId;
        boolean docOk = stagedHere || cacheCurrent;
        int sides = stagedHere ? (stagedTwoSided ? 2 : 1) : cacheCurrent ? cachedPilotSides() : 1;
        Path installRoot = JoinVoiceSettings.installRoot(modDir);
        JoinCore.LocalCustoms local = JoinCore.localCustomReport(installRoot);
        JoinCore.publishVariantIndex(installRoot, local);
        // The published mod set vs. the joiner's own mods folder (file IO + jar
        // digests, on this request's thread like the customs scan)
        JoinModStage.Diff modDiff = JoinModStage.diff(result.mods, result.modsAvailable, result.modsComplete,
                JoinModStage.scan(installRoot));
        String line = windowLine(result);
        if (!local.ids.isEmpty()) {
            line = line + " + " + local.ids.size() + " local custom(s)";
        }
        // The per-custom SERVER verdict rides the served probe —
        // the only reply with server contact (api/state never dials). Same generation guard: a
        // superseded fetch's verdicts must not land on a newer form.
        String verdicts = local.ids.isEmpty() ? null : JoinCore.verdictsJson(
                JoinCore.customVerdicts(local, h, portNo, pw, print.print));
        if (gen != probeGen.get()) {
            return new JoinJson().put("ok", true).put("status", "stale").toString();
        }
        // Launch state lands only past the SECOND generation check — a probe superseded
        // during customVerdicts (the second network call) must not key the launch gates to its host:port.
        fetchedDefault = result.defaultId;
        probed = new JoinLaunchGates.Probed(h, portNo, result.defaultId, modDiff);
        lastProbe = new JoinLaunchGates.ProbeOutcome(JoinLaunchGates.ProbeOutcome.Kind.SERVED, h, portNo, line, docOk,
                docLine, cacheCurrent);
        // iteration 9 (owner #4): the editor's SERVER BUILDS panel reads this served list off one published file
        JoinCore.publishServerBuilds(installRoot, result.ids, result.defaultId, System.currentTimeMillis());
        // The ship rows' display labels as {id: name} — the page's ship list paints the name,
        // the picked VALUE stays the id (the browser lane is the shipped joiner; the Swing combo got ShipChoiceNames)
        // + {id: hull id} the same way: the list paints and searches name, hull and id
        return j.put("status", "served").put("line", line).putStrings("ids", result.ids)
                .putRaw("names", idMapJson(result.names)).putRaw("hulls", idMapJson(result.hulls))
                .put("defaultId", result.defaultId).put("hidden", result.hidden)
                .put("totalAvailable", result.totalAvailable)
                .putStrings("localCustoms", local.ids).putStrings("customsNotes", local.notes)
                .putRaw("customsVerdicts", verdicts)
                .put("modsLine", modDiff.line).put("modsOk", modDiff.ok)
                .putRaw("mods", JoinModStage.rowsJson(modDiff))
                .put("docLine", docLine).put("docOk", docOk).putRaw("staged", stagedHere ? stagedSummaryJson : null)
                .put("sides", sides)
                .toString();
    }

    /**
     * One {id: label} object for the served reply ({@code names}, {@code hulls}), or null when the map is empty — the
     * key then reads {@code null} and the page paints the ids as before (an older server sends no labels). Values are
     * JSON-escaped by
     * {@link JoinJson#put}; the page lands them as textContent only.
     */
    static String idMapJson(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        JoinJson out = new JoinJson();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        return out.toString();
    }

    /**
     * The cached match file's pilot sides for the probe's {@code sides}: 2 when it seats a pilot on BOTH sides
     * ({@link MatchDescriptor#seatsPilotOnBothSides}, the same test the server's door honours a team pick under), else
     * 1 — a missing, over-cap or undecodable file is 1 (no Team choice; the server picks). Read only when the probe
     * found the cache current, so the file is the match the server serves. Blocking file read, this request's thread.
     *
     */
    private int cachedPilotSides() {
        Path root = modDir.toAbsolutePath().normalize();
        for (String name : new String[] {"match.txt", "match.txt.txt"}) {
            Path candidate = root.resolve(name);
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                String text = JoinFingerprint.readCappedKitText(candidate);
                return text != null && MatchDescriptor.seatsPilotOnBothSides(JoinLaunchPlan.parseDescriptor(text))
                        ? 2 : 1;
            } catch (Exception unreadable) {
                log("join-browser: the cached match did not read for the team choice ("
                        + unreadable.getClass().getSimpleName() + ")");
                return 1;
            }
        }
        return 1;
    }

    /**
     * The ONE password both routes send — the typed field (sanitized) when
     * non-empty, else the password remembered for exactly this request's {@code host:port} ({@code ""} for any other
     * pair: a remembered password never reaches a server it was not saved for). Per request, never at open.
     */
    private String resolvePassword(String typed, String host, String port) {
        String pw = JoinLaunchPlan.sanitizePassword(typed);
        return pw.isEmpty() ? saved.rememberedPasswordFor(host, port) : pw;
    }

    /** api/launch: {@link JoinLaunchGates} check (the v1 gates, in order) then commit (the v1 file
     *  hand-off, byte-for-byte); one launch per session (the lock + the state CAS); an empty password field means
     *  the one remembered for the typed host:port ({@link #resolvePassword}). On success the page becomes the
     *  launch face and {@link #companionLoop} takes over. */
    public String launch(String host, String port, String name, String password, String ship, boolean logs,
            JoinVoiceCore.Pick voice) {
        return launch(host, port, name, password, ship, logs, voice, JoinLaunchPlan.TEAM_SERVER_PICKS);
    }

    /** Api/launch with the page's Team choice ({@code team} as the route
     *  read it: 0..2, or {@link JoinRoutes#TEAM_UNLAWFUL} for the gate to refuse); the 7-arg form = the server picks. */
    @Override
    public String launch(String host, String port, String name, String password, String ship, boolean logs,
            JoinVoiceCore.Pick voice, int team) {
        synchronized (launchLock) {
            if (!inForm()) {
                return refusalJson(GATE_STATE, "Already launched", MSG_ALREADY_LAUNCHED, false);
            }
            String pw = resolvePassword(password, host, port);
            // The staged document rides only to the pair that served it;
            // with none, the gate reads the last probe's outcome for the launch's pair
            String document = stagedCredential != null && JoinLaunchGates.samePair(stagedHost, stagedPort, host, port)
                    ? stagedCredential : null;
            JoinLaunchGates.Verdict verdict = JoinLaunchGates.check(
                    new JoinLaunchGates.Form(host, port, name, pw, ship, document, logs, team), modDir, lastProbe);
            if (verdict.refusal != null) {
                return refusalJson(verdict.refusal);
            }
            // L5: the Test worker stops and the mic keys ride the shared voice file BEFORE the plan is written (the
            // Swing panel's merge, same file law); a write failure = the hand-off's WARNING, never a refusal
            tester.stop();
            final List<Mixer.Info> list = mixers;
            JoinLaunchGates.VoiceWriter writer = voice == null ? null : () -> JoinVoiceSettings.write(voiceFile,
                    JoinVoiceSettings.merge(JoinVoiceSettings.read(voiceFile),
                            JoinVoiceCore.deviceValue(list, voice.device), voice.on, voice.gainFactor(),
                            voice.complexity));
            JoinLaunchGates.Probed asked = probed; // keyed: another pair's probe hands over nothing
            JoinLaunchGates.Handoff handoff = JoinLaunchGates.commit(verdict.ready, modDir, planFile, settingsPath,
                    asked.defaultFor(verdict.ready), logs, writer, asked.modDiffFor(verdict.ready));
            if (handoff.refusal != null) {
                return refusalJson(handoff.refusal);
            }
            launchedJson = new JoinJson()
                    .put("host", verdict.ready.host)
                    .put("port", verdict.ready.port)
                    .put("name", JoinLaunchPlan.sanitizeName(verdict.ready.name))
                    .put("ship", handoff.resolvedShip)
                    .toString();
            companionVerdict = "waiting";
            companionLine = JoinCore.companionWaitingLine(0L);
            state.set(STATE_LAUNCHED);
            log("join-browser: launched (plan written); following the game");
            Thread poller = new Thread(this::companionLoop, "cc-editor-face-poll");
            poller.setDaemon(true);
            poller.start();
            return new JoinJson().put("ok", true).put("launched", true).put("ship", handoff.resolvedShip)
                    .put("warning", handoff.warning).toString();
        }
    }

    /** api/editor: ONE fresh ping through the game's endpoint file bound by OUR nonce
     *  — the URL + the live line when the game answers; else {@code ok:false} with the form's "launch first" or the
     *  face's current companion line (the SPA lands either via textContent). */
    @Override
    public String editor() {
        JoinCore.EditorPing ping = JoinCore.liveEditorPing(JoinCore.editorEndpointFile(modDir), nonce);
        if (ping == null) {
            String line = companionLine;
            return JoinJson.error(inForm() || line == null ? EDITOR_LAUNCH_FIRST : line);
        }
        String state = JoinCore.pingField(ping.body, "state");
        return new JoinJson().put("ok", true).put("editor", ping.base).put("state", state)
                .put("line", JoinCore.companionStatusLine(state, JoinCore.pingField(ping.body, "session")))
                .toString();
    }

    /** api/voice/devices: enumerate now (the Swing panel enumerates at open), remember the list for the index law,
     *  preselect the saved device. */
    @Override
    public String voiceDevices() {
        List<Mixer.Info> list = JoinVoiceCore.captureMixers();
        mixers = list;
        return JoinVoiceCore.devicesJson(list, JoinVoiceSettings.read(voiceFile).micDevice);
    }

    /** api/voice/test: {@code start} (device index into the enumerated list, gain percent) / {@code stop} /
     *  {@code gain} (live, no restart); the Test never runs once the plan is the launcher's. */
    @Override
    public String voiceTest(String action, String device, String gain) {
        String a = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        if ("stop".equals(a)) {
            tester.stop();
            voiceLevel = 0;
            voiceLine = "";
        } else if ("gain".equals(a)) {
            tester.gain = JoinVoiceCore.parseBounded(gain, "gain", JoinVoiceCore.GAIN_PERCENT_DEFAULT, 0,
                    JoinVoiceCore.GAIN_PERCENT_MAX) / 100f;
        } else if ("start".equals(a)) {
            if (!inForm()) {
                return JoinJson.error(MSG_ALREADY_LAUNCHED);
            }
            int idx = JoinVoiceCore.parseBounded(device, "device", 0, 0, 999);
            tester.gain = JoinVoiceCore.parseBounded(gain, "gain", JoinVoiceCore.GAIN_PERCENT_DEFAULT, 0,
                    JoinVoiceCore.GAIN_PERCENT_MAX) / 100f;
            voiceLevel = 0;
            tester.start(JoinVoiceCore.mixerAt(mixers, idx), voiceListener);
        } else {
            throw new IllegalArgumentException("field action is malformed");
        }
        return voiceLevel();
    }

    @Override
    public String voiceLevel() {
        return new JoinJson().put("ok", true).put("running", tester.running()).put("level", voiceLevel)
                .put("line", voiceLine).toString();
    }

    /** The faces the join page may wear (the editor's own allowlist, minus the unused black weight). */
    static final java.util.Set<String> FONTS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList("orbitron-bold", "orbitron-light")));
    static final int FONT_MAX_BYTES = 2 * 1024 * 1024;

    /** {@code graphics/fonts/<name>.ttf} from the install for an allowlisted name; null when off the list,
     *  missing, oversize or unreadable (the page falls back to its body face). Never copied anywhere. */
    @Override
    public byte[] font(String name) {
        return fontBytes(JoinVoiceSettings.installRoot(modDir), name);
    }

    static byte[] fontBytes(Path installRoot, String name) {
        if (name == null || !FONTS.contains(name)) {
            return null;
        }
        try {
            Path ttf = installRoot.resolve("graphics").resolve("fonts").resolve(name + ".ttf");
            if (!Files.isRegularFile(ttf) || Files.size(ttf) > FONT_MAX_BYTES) {
                return null;
            }
            return Files.readAllBytes(ttf);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String refusalJson(JoinLaunchGates.Refusal r) {
        return refusalJson(r.gate, r.title, r.message, r.error);
    }

    private static String refusalJson(String gate, String title, String message, boolean error) {
        return new JoinJson().put("ok", false).put("gate", gate).put("title", title).put("error", message)
                .put("severe", error).toString();
    }

    /**
     * The Swing face's poller ({@code JoinGui.showLaunchFace}), verbatim in law: {@code api/ping} through the
     * RUNNING game's endpoint file, bound to this launch by the nonce (a foreign or nonce-less file keeps it
     * waiting); the status line = the live line from the ping body / the waiting clock / the not-answering line;
     * the verdict {@code JoinCore.companionVerdict}; {@code exit} ⇒ this JVM leaves with code 0.
     */
    private void companionLoop() {
        Path endpoint = JoinCore.editorEndpointFile(modDir);
        int seenLive = 0;
        int misses = 0;
        long started = System.currentTimeMillis();
        while (done.getCount() > 0) {
            JoinCore.EditorPing ping = JoinCore.liveEditorPing(endpoint, nonce);
            boolean isLive = ping != null;
            if (isLive) {
                seenLive++;
                misses = 0;
            } else if (seenLive > 0) {
                misses++;
            }
            long elapsed = System.currentTimeMillis() - started;
            String verdict = JoinCore.companionVerdict(isLive, seenLive, misses, elapsed);
            String line = isLive
                    ? JoinCore.companionStatusLine(JoinCore.pingField(ping.body, "state"),
                            JoinCore.pingField(ping.body, "session"))
                    : seenLive > 0 ? LINE_NOT_ANSWERING : JoinCore.companionWaitingLine(elapsed);
            editorUrl = isLive ? ping.base : null;
            if (isLive) {
                companionSeenLive = true;
                // iteration 9 (c): the editor opens by itself on the FIRST live answer — once per launch, a
                // Java Desktop.browse (never a page-side window.open outside a click); the page's Open editor
                // button stays the fallback when the browse fails or the opt-out is set
                if (!editorAutoOpened && JoinCore.autoEditorEnabled()) {
                    editorAutoOpened = true;
                    if (openBrowser(ping.base)) {
                        // The port only - the base URL carries the editor's token
                        System.out.println("editor auto-opened (port " + URI.create(ping.base).getPort() + ")");
                    } else {
                        log("join-browser: editor auto-open failed - use the page's Open editor button");
                    }
                }
            }
            companionLine = line;
            companionVerdict = verdict;
            if ("exit".equals(verdict)) {
                log("join-browser: the game " + (seenLive > 0 ? "closed" : "never answered")
                        + " - leaving");
                finish(Outcome.exit(0));
                return;
            }
            try {
                Thread.sleep(isLive ? JoinCore.COMPANION_POLL_LIVE_MS : JoinCore.COMPANION_POLL_WAITING_MS);
            } catch (InterruptedException stop) {
                return;
            }
        }
    }
}
